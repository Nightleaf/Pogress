package net.runelite.client.plugins.pogress;

import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemManager;

import javax.inject.Inject;

public class LootContainer {

    // The type of the event such as what activity the loot came from
    private EventType type;

    // The overall container of loot that is received from doing an activity
    private Item[] items;

    // The value of all the loot
    private int value;

    public LootContainer(EventType type, Item[] items, int value) {
        this.type = type;
        this.items = items;
        this.value = value;
    }

    public EventType getType() {
        return type;
    }

    public Item[] getItems() {
        return items;
    }

    public int getValue() {
        return value;
    }

}
enum EventType
{
    CLUE,
    BARROWS,
    KINGDOM,
    MINI_GAME,
    UNKNOWN
}

