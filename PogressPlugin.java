package net.runelite.client.plugins.pogress;

import com.google.common.collect.ImmutableList;
import com.google.gson.JsonObject;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetID;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientUI;
import net.runelite.client.util.ImageCapture;
import net.runelite.client.util.QuantityFormatter;
import net.runelite.client.util.Text;
import net.runelite.http.api.RuneLiteAPI;
import okhttp3.*;

import javax.inject.Inject;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.runelite.api.widgets.WidgetID.*;

@PluginDescriptor(
        name = "Pogress",
        description = "Enable the sharing of account progress over a Discord chat channel.",
        tags = {"pogress"},
        enabledByDefault = false
)
@Slf4j
public class PogressPlugin extends Plugin {

    private static final Pattern NUMBER_PATTERN = Pattern.compile("([0-9]+)");
    private static final Pattern LEVEL_UP_PATTERN = Pattern.compile(".*Your ([a-zA-Z]+) (?:level is|are)? now (\\d+)" +
            "\\.");
    private static final Pattern BOSSKILL_MESSAGE_PATTERN = Pattern.compile("Your (.+) kill count is: <col=ff0000>" +
            "(\\d+)</col>.");
    private static final Pattern VALUABLE_DROP_PATTERN = Pattern.compile(".*Valuable drop: ([^<>]+)(?:</col>)?");
    private static final Pattern UNTRADEABLE_DROP_PATTERN = Pattern.compile(".*Untradeable drop: ([^<>]+)(?:</col>)?");
    private static final Pattern DUEL_END_PATTERN = Pattern.compile("You have now (won|lost) ([0-9]+) duels?\\.");
    private static final ImmutableList<String> PET_MESSAGES = ImmutableList.of("You have a funny feeling like you're " +
                    "being followed",
            "You feel something weird sneaking into your backpack",
            "You have a funny feeling like you would have been followed");
    private static final String CHAMPION_SCROLL_MESSAGE = "A Champion's scroll falls to the ground as you slay your " +
            "opponent.";
    public List<PogressUpdate> pogressUpdates = new ArrayList<>();
    private String lastMonsterKilled;
    private int lastMonsterCombatLevel;
    private ItemContainer lastContainer;
    private boolean wouldTakeScreenshot;
    private String clueType;
    private Integer clueCompletions;
    private Integer barrowsChestCompletions;

    public LootContainer lastClueContainer;
    public LootContainer lastBarrowsContainer;
    public LootContainer lastMinigameContainer;
    public LootContainer lastLoot;

    @Inject
    private Client client;
    @Inject
    private ClientUI clientUi;
    @Inject
    private ImageCapture imageCapture;
    @Inject
    private ScheduledExecutorService executor;
    @Inject
    private ChatMessageManager chatMessageManager;
    @Inject
    private PogressConfig config;
    @Inject
    private ItemManager itemManager;

    @Override
    protected void startUp() throws Exception {

    }

    @Subscribe
    public void onNpcLootReceived(final NpcLootReceived npcLootReceived) {
        final NPC npc = npcLootReceived.getNpc();
        final Collection<ItemStack> items = npcLootReceived.getItems();
        final String name = npc.getName();
        final int combat = npc.getCombatLevel();
        lastMonsterKilled = name;
        lastMonsterCombatLevel = combat;
    }

    @Subscribe
    public void onChatMessage(ChatMessage event) {
        if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM && event.getType() != ChatMessageType.TRADE) {
            return;
        }
        String chatMessage = event.getMessage();
        if (chatMessage.contains(CHAMPION_SCROLL_MESSAGE)) {
            addChampionScrollUpdate(chatMessage);
        }
        /**
         * Treasure trails
         */
        if (chatMessage.contains("You have completed") && chatMessage.contains("Treasure")) {
            Matcher m = NUMBER_PATTERN.matcher(Text.removeTags(chatMessage));
            if (m.find()) {
                clueCompletions = Integer.valueOf(m.group());
                clueType = chatMessage.substring(chatMessage.lastIndexOf(m.group()) + m.group().length() + 1,
                        chatMessage.indexOf("Treasure") - 1);
                return;
            }
        }

        if (chatMessage.startsWith("Your Barrows chest count is")) {
            Matcher m = NUMBER_PATTERN.matcher(Text.removeTags(chatMessage));
            if (m.find()) {
                barrowsChestCompletions = Integer.valueOf(m.group());
                return;
            }
        }

        Matcher m = VALUABLE_DROP_PATTERN.matcher(chatMessage);
        if (m.matches()) {
            String valuableDropName = m.group(1);
            addValuableDropUpdate(valuableDropName, lastMonsterKilled, lastMonsterCombatLevel);
            return;
        }

        Matcher um = UNTRADEABLE_DROP_PATTERN.matcher(chatMessage);
        if (um.matches()) {
            addUntradeableDropUpdate(chatMessage);
        }

        if (PET_MESSAGES.stream().anyMatch(chatMessage::contains)) {
            addPetRewardUpdate();
        }

        if (config.shareBossKills()) {
            try {
                Matcher ma = BOSSKILL_MESSAGE_PATTERN.matcher(chatMessage);
                if (ma.matches()) {
                    String boss = ma.group(1);
                    String killCount = ma.group(2);
                    addBossKillUpdate(boss, killCount);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Subscribe
    public void onGameTick(GameTick event) {
        handleUpdates();
        if (!wouldTakeScreenshot) {
            return;
        }
        wouldTakeScreenshot = false;
        if (client.getWidget(WidgetInfo.LEVEL_UP_LEVEL) != null) {
            String[] levelUp = parseLevelUpWidgetArr(WidgetInfo.LEVEL_UP_LEVEL); // Skill_name then level
            int totalLevel = client.getTotalLevel();
            addLevelUpUpdate(levelUp[0], Integer.parseInt(levelUp[1]), totalLevel);
        } else if (client.getWidget(WidgetInfo.DIALOG_SPRITE_TEXT) != null) {
            String[] levelUp = parseLevelUpWidgetArr(WidgetInfo.DIALOG_SPRITE_TEXT); // Skill_name then level
            int totalLevel = client.getTotalLevel();
            if (levelUp != null) {
                addLevelUpUpdate(levelUp[0], Integer.parseInt(levelUp[1]), totalLevel);
            }
        } else if (client.getWidget(WidgetInfo.QUEST_COMPLETED_NAME_TEXT) != null) {
            // "You have completed The Corsair Curse!"
            String text = client.getWidget(WidgetInfo.QUEST_COMPLETED_NAME_TEXT).getText();
            String questName = text.substring(19, text.length() - 1);
            addQuestUpdate(questName);
        }
    }

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded wid) {
        int groupId = wid.getGroupId();
        EventType type = EventType.UNKNOWN;
        switch (groupId) {
            case (WidgetID.BARROWS_REWARD_GROUP_ID):
                type = EventType.BARROWS;
                lastContainer = client.getItemContainer(InventoryID.BARROWS_REWARD);
                break;
            case (WidgetID.CLUE_SCROLL_REWARD_GROUP_ID): // Same inventory id as Barrows
                type = EventType.CLUE;
                lastContainer = client.getItemContainer(InventoryID.BARROWS_REWARD);
                break;
            case KINGDOM_GROUP_ID:
                type = EventType.KINGDOM;
                lastContainer = client.getItemContainer(InventoryID.KINGDOM_OF_MISCELLANIA);
                addKingdomRewardUpdate();
                break;
            case (WidgetID.FISHING_TRAWLER_REWARD_GROUP_ID):
                type = EventType.MINI_GAME;
                lastContainer = client.getItemContainer(InventoryID.FISHING_TRAWLER_REWARD);
                break;
            case (WidgetID.DRIFT_NET_FISHING_REWARD_GROUP_ID):
                type = EventType.MINI_GAME;
                lastContainer = client.getItemContainer(InventoryID.DRIFT_NET_FISHING_REWARD);
                break;

            case LEVEL_UP_GROUP_ID:
            case DIALOG_SPRITE_GROUP_ID:
            case QUEST_COMPLETED_GROUP_ID:
                // level up widget gets loaded prior to the text being set, so wait until the next tick
                wouldTakeScreenshot = true;
                break;
        }

        if (lastContainer != null) {
            Item[] containerItems = lastContainer.getItems();
            int value = getValueOfItems(containerItems);
            LootContainer loot = new LootContainer(type, containerItems, value);
            System.out.println("[Pogress] Creating a loot container - Event type = " + type.toString() + ", # of " +
                    "items = " + containerItems.length + ", value = " + value);
            if (type == EventType.BARROWS) {
                lastBarrowsContainer = loot;
                addBarrowsUpdate();
            }
            if (type == EventType.CLUE) {
                lastClueContainer = loot;
                addClueUpdate();
            }
            if (type == EventType.MINI_GAME) {
                lastMinigameContainer = loot;
            }
            if (type == EventType.UNKNOWN) {
                lastLoot = loot;
            }
        }
        lastContainer = null;
    }

    /**
     * Sends an update showing a clue competion to Discord.
     */
    public void addClueUpdate() {
        String message =
                "**" + getPlayerName() + "** has completed **" + clueType + "** Treasure Trails " + clueCompletions +
                        " times.";
        LootContainer loot = lastClueContainer;
        if (loot != null) {
            if (loot.getValue() > 0) { // Prevent broken values from being posted
                message = message + " The value of the loot was " + QuantityFormatter.formatNumber(loot.getValue()) +
                        " gp.";
            }
        }
        if (config.shareClues())
            addUpdate(message);
    }

    /**
     * Sends an update showing that the player has completed a Barrows run to a Discord channel.
     */
    public void addBarrowsUpdate() {
        int count = barrowsChestCompletions;
        String message = "**" + getPlayerName() + "** just completed a **Barrows** run for a total of **" + count +
                "** times";
        LootContainer loot = lastBarrowsContainer;
        if (loot != null) {
            boolean worthyOfShare = false;
            for (Item item : loot.getItems()) {
                ItemComposition composition = itemManager.getItemComposition(item.getId());
                String name = composition.getName().toLowerCase();
                if (name.contains("ahrim") || name.contains("dharok") || name.contains("guthan") || name.contains(
                        "karil") || name.contains("torag") || name.contains("verac")) {
                    worthyOfShare = true;
                }
            }

            int value = loot.getValue();
            if (value > config.barrowsValueThreshold()) {
                message =
                        "**" + getPlayerName() + "** has completed a **Barrows** run worth **" + QuantityFormatter.formatNumber(value) + "** " +
                                "gp " +
                                "for a total of  " + count + " times.";
            } else {
                worthyOfShare = false;
            }
            if (config.shareBarrows()) {
                System.out.println("[Pogress] BARROWS DEBUGGING START");
                System.out.println("Chest Count = " + count);
                System.out.println("Amount of items = " + loot.getItems().length);
                System.out.println("Value of loot = " + value);
                System.out.println("[Pogress] END OF BARROWS DEBUGGING");
                if (worthyOfShare)
                    addUpdate(message);
            }
        }
    }

    /**
     * Sends a message showing that the player has completed a Barrows run in OSRS to a Discord channel.
     */
    public void addValuableDropUpdate(String valuableDrop, String monsterName, int level) {
        String editedMessage = "**" + getPlayerName() + "** received a valuable drop: **" + valuableDrop + "**";
        if (config.shareValuableDrops()) {
            addUpdate(editedMessage);
        }
    }

    /**
     * Sends a message showing an untradeable drop to Discord.
     *
     * @param message
     */
    public void addUntradeableDropUpdate(String message) {
        String cleanMessage = message.substring(message.indexOf("U"));
        cleanMessage = cleanMessage.replace("</col>", "");
        String item = cleanMessage.substring(cleanMessage.indexOf(":") + 1);
        item = item.replaceAll(" ", "");

        String eventMessage = "**" + getPlayerName() + "** received an untradeable drop: **" + item + "**!";
        if (config.shareUntradeableDrops()) {
            boolean worthy = false;
            String[] acceptableItems = new String[]{
                    "Curved Bone", "Long Bone", "Dragon Defender"
            };
            for (String s : acceptableItems) {
                if (item.contains(s)) {
                    worthy = true;
                }
            }
            if (worthy)
                addUpdate(eventMessage);
        }
    }

    /**
     * Sends a message of a champion scroll drop to the Discord channel.
     *
     * @param message
     */
    public void addChampionScrollUpdate(String message) {
        String cleanString = message.substring(message.indexOf("A"));
        cleanString = cleanString.replace("</col>", "");
        cleanString = cleanString.replace("slay", "slays");
        cleanString = cleanString.replace("your", "their");
        cleanString = cleanString.replace("you", "**" + getPlayerName() + "**");

        if (config.shareUntradeableDrops())
            addUpdate(cleanString);
    }

    /**
     * Sends a level up update to the Discord channel.
     *
     * @param skill The skill that was leveled up.
     * @param level The new level.
     */
    public void addLevelUpUpdate(String skill, int level, int totalLevel) {
        String editedMessage =
                "**" + getPlayerName() + "** has gained a level in **" + skill + "** and is now level **" + level +
                        "**.";
        int rem = totalLevel % 100;
        if (totalLevel > 500) {
            if (rem == 0) {
                editedMessage = editedMessage + "  Their total level is now " + totalLevel + "!";
            }
        }
        if (config.shareLevelUp()) {
            if (config.levelUpThreshold() > level)
                return;
            addUpdate(editedMessage);
        }
    }

    /**
     * Sends a quest completion update to the Discord channel.
     *
     * @param questName The name of the quest that was completed.
     */
    public void addQuestUpdate(String questName) {
        String editedMessage = "**" + getPlayerName() + "** has completed **" + questName + "**.";
        if (config.shareQuestComplete()) {
            addUpdate(editedMessage);
        }
    }

    /**
     * Sends a pet update to the Discord channel.
     */
    public void addPetRewardUpdate() {
        String editedMessage = "@everyone **" + getPlayerName() + "** has received a pet!";
        if (config.sharePetDrop()) {
            addUpdate(editedMessage);
        }
    }

    /**
     * Sends a pet update to the Discord channel.
     */
    public void addBossKillUpdate(String boss, String killCount) {
        try {
            int kc = Integer.parseInt(killCount);
            String message = "**" + getPlayerName() + "** has killed **" + boss + "** " + killCount +
                    " times.";
            boolean worthy = false;
            if (config.shareBossKills()) {
                BossFilter filter = config.bossKillFilter();
                if (filter == BossFilter.FIRST_KILL_ONLY) {
                    if (kc == 1) {
                        worthy = true;
                    }
                } else if (filter == BossFilter.MILESTONE_KILLS) {
                    int remainder = kc % 50;
                    if (remainder == 0) {
                        worthy = true;
                    }
                }
                if (worthy)
                    addUpdate(message);
            }
        } catch (NumberFormatException e) {
            e.printStackTrace();
        }
    }

    /**
     * Sends a Miscellania reward update to the Discord channel.
     */
    public void addKingdomRewardUpdate() {
        String editedMessage = "**" + getPlayerName() + "** has collected resources from **Miscellania**.";
        ItemContainer loot = lastContainer;
        if (loot != null) {
            int value = getValueOfItems(loot.getItems());
            if (value > 0) {
                editedMessage =
                        "**" + getPlayerName() + "** has collected **" + QuantityFormatter.formatNumber(value) + "** " +
                                "gp " +
                                "worth of resources from " +
                                "**Miscellania**. ";
            }
        }
        if (config.shareMiscReward()) {
            addUpdate(editedMessage);
        }
    }

    private void addUpdate(String message) {
        PogressUpdate update = new PogressUpdate(message);
        if (pogressUpdates.contains(update)) {
            return;
        }
        pogressUpdates.add(update);
    }

    /**
     * Handles the timing of all the messages that get sent to discord
     */
    private void handleUpdates() {
        if (pogressUpdates == null || pogressUpdates.size() <= 0) {
            return;
        }
        List<PogressUpdate> toRemove = new ArrayList<>();
        for (int i = 0; i < pogressUpdates.size(); i++) {
            PogressUpdate update = pogressUpdates.get(i);
            if (update.tickDelay > 0) {
                update.setTickDelay(update.tickDelay - 1);

            } else if (update.tickDelay == 0) {
                sendPogressUpdate(update.getUpdateMessage());
                toRemove.add(update);
            }
        }
        // Remove all the updates that have played
        for (int i = 0; i < toRemove.size(); i++) {
            PogressUpdate update = toRemove.get(i);
            if (pogressUpdates.contains(update)) {
                pogressUpdates.remove(update);
            }
        }
    }

    /**
     * Send an update to the community discord chat!
     *
     * @param message The message being posted in the chat channel!
     */
    private void sendPogressUpdate(String message) {
        Player local = client.getLocalPlayer();

        String reformedUsername = local.getName().replace(" ", "_");
        OkHttpClient httpClient = RuneLiteAPI.CLIENT;

        String clipboard = getClipboardContents();
        if (clipboard.toLowerCase().contains("imgur")) {
            message = message + " " + clipboard;
        }

        JsonObject jsonObj = new JsonObject();
        jsonObj.addProperty("content", message);
        String json = jsonObj.toString();
        HttpUrl url = new HttpUrl.Builder()
                .scheme("https")
                .host("discordapp.com")
                .addPathSegment("api")
                .addPathSegment("webhooks")
                .addPathSegment("696913917779247160")
                .addPathSegment("WuqMNMjKcXCTFTMordBOfYWjtnIl81GbFF1z5R3BUmnECXGFYwe19ePKmrD8O3LuWsHu")
                .build();

        MediaType JSON = MediaType.parse("application/json; charset=utf-8");
        RequestBody body = RequestBody.create(JSON, json);
        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .build();
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.warn("Error sending progress update to discord! [POGRESS]");
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                response.close();
            }
        });
    }

    /**
     * Receives a WidgetInfo pointing to the middle widget of the level-up dialog,
     * and parses it into a shortened string for filename usage.
     *
     * @param levelUpLevel WidgetInfo pointing to the required text widget,
     *                     with the format "Your Skill (level is/are) now 99."
     * @return as an array to be able to send to the discord channel easily
     */
    private String[] parseLevelUpWidgetArr(WidgetInfo levelUpLevel) {
        Widget levelChild = client.getWidget(levelUpLevel);
        if (levelChild == null) {
            return null;
        }

        Matcher m = LEVEL_UP_PATTERN.matcher(levelChild.getText());
        if (!m.matches()) {
            return null;
        }

        String skillName = m.group(1);
        String skillLevel = m.group(2);
        return new String[]{skillName, skillLevel};
    }

    /**
     * Receives a WidgetInfo pointing to the middle widget of the level-up dialog,
     * and parses it into a shortened string for filename usage.
     *
     * @param levelUpLevel WidgetInfo pointing to the required text widget,
     *                     with the format "Your Skill (level is/are) now 99."
     * @return Shortened string in the format "Skill(99)"
     */
    private String parseLevelUpWidget(WidgetInfo levelUpLevel) {
        Widget levelChild = client.getWidget(levelUpLevel);
        if (levelChild == null) {
            return null;
        }

        Matcher m = LEVEL_UP_PATTERN.matcher(levelChild.getText());
        if (!m.matches()) {
            return null;
        }

        String skillName = m.group(1);
        String skillLevel = m.group(2);
        return skillName + "(" + skillLevel + ")";
    }

    private String getPlayerName() {
        return client.getLocalPlayer().getName();
    }

    /**
     * Gets the total value of an array of items.
     *
     * @param items The items being price-checked.
     * @return The total value.
     */
    private int getValueOfItems(Item[] items) {
        int value = 0;
        for (Item item : items) {
            ItemComposition comp = itemManager.getItemComposition(item.getId());
            int itemId = item.getId();
            int realItemId = comp.getNote() != -1 ? comp.getLinkedNoteId() : itemId;
            value += itemManager.getItemPrice(realItemId) * item.getQuantity();
        }
        return value;
    }

    /**
     * Get the String residing on the clipboard.
     *
     * @return any text found on the Clipboard; if none found, return an
     * empty String.
     */
    private String getClipboardContents() {
        String result = "";
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        //odd: the Object param of getContents is not currently used
        Transferable contents = clipboard.getContents(null);
        boolean hasTransferableText =
                (contents != null) &&
                        contents.isDataFlavorSupported(DataFlavor.stringFlavor);
        if (hasTransferableText) {
            try {
                result = (String) contents.getTransferData(DataFlavor.stringFlavor);
            } catch (UnsupportedFlavorException | IOException ex) {
                System.out.println(ex);
                ex.printStackTrace();
            }
        }
        return result;
    }

    @Provides
    PogressConfig getConfig(ConfigManager configManager) {
        return configManager.getConfig(PogressConfig.class);
    }
}
