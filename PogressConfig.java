package net.runelite.client.plugins.pogress;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

/*
 * runelite-parent
 *
 * Copyright (C) 2020 Nightleaf.org
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with runelite-parent.  If not, see <https://www.gnu.org/licenses/>.
 *
 */
@ConfigGroup("pogress")
public interface PogressConfig extends Config {
    @ConfigItem(
            keyName = "shareBarrows",
            name = "Share Barrows",
            description = "Configures whether an update is posted for Barrows to Discord."
    )
    default boolean shareBarrows()
    {
        return true;
    }
    @ConfigItem(
            keyName = "shareValuableDrops",
            name = "Share Valuable Drops",
            description = "Configures whether an update is posted for valuable drops to Discord."
    )
    default boolean shareValuableDrops()
    {
        return true;
    }
    @ConfigItem(
            keyName = "shareLevelUp",
            name = "Share Level Up",
            description = "Configures whether an update is posted for a level-up to Discord."
    )
    default boolean shareLevelUp()
    {
        return true;
    }
    @ConfigItem(
            keyName = "levelUpThreshold",
            name = "Level Up Threshold",
            description = "Configures the minimum level on level-up for a post to Discord."
    )
    default int levelUpThreshold()
    {
        return 30;
    }
    @ConfigItem(
            keyName = "barrowsValueThreshold",
            name = "Barrows Value Threshold",
            description = "Configures the value threshold with a Barrows item in the chest to be posted to Discord."
    )
    default int barrowsValueThreshold()
    {
        return 50000;
    }
    @ConfigItem(
            keyName = "shareQuestComplete",
            name = "Share Quest Completed",
            description = "Configures whether an update is posted for a quest completion to Discord."
    )
    default boolean shareQuestComplete()
    {
        return true;
    }
    @ConfigItem(
            keyName = "bossKillFilter",
            name = "Boss Filter",
            description = "Configures how boss kills should be filtered for posting to Discord."
    )
    default BossFilter bossKillFilter()
    {
        return BossFilter.FIRST_KILL_ONLY;
    }
    @ConfigItem(
            keyName = "shareBossKills",
            name = "Share Boss Kills",
            description = "Configures whether an update is posted for when a boss is killed to Discord."
    )
    default boolean shareBossKills()
    {
        return true;
    }
    @ConfigItem(
            keyName = "shareUntradeableDrops",
            name = "Share Untradeable Drops",
            description = "Configures whether an update is posted for specific untradeables that are significant to " +
                    "account progress to Discord."
    )
    default boolean shareUntradeableDrops() {
        return true;
    }
    @ConfigItem(
            keyName = "shareClues",
            name = "Share Clues",
            description = "Configures whether an update is posted for clue rewards to Discord."
    )
    default boolean shareClues() {
        return true;
    }
    @ConfigItem(
            keyName = "sharePetDrop",
            name = "Share Pet Drop",
            description = "Configures whether an update is posted for when a pet drops to Discord."
    )
    default boolean sharePetDrop()
    {
        return true;
    }
    @ConfigItem(
            keyName = "shareMiscReward",
            name = "Share Miscellania Rewards",
            description = "Configures whether an update is posted for when you collect from Miscellania to " +
                    "Discord."
    )
    default boolean shareMiscReward()
    {
        return true;
    }
}
