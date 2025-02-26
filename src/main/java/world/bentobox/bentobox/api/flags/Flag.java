package world.bentobox.bentobox.api.flags;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;

import world.bentobox.bentobox.BentoBox;
import world.bentobox.bentobox.api.addons.Addon;
import world.bentobox.bentobox.api.addons.GameModeAddon;
import world.bentobox.bentobox.api.configuration.WorldSettings;
import world.bentobox.bentobox.api.flags.clicklisteners.CycleClick;
import world.bentobox.bentobox.api.flags.clicklisteners.IslandToggleClick;
import world.bentobox.bentobox.api.flags.clicklisteners.WorldToggleClick;
import world.bentobox.bentobox.api.localization.TextVariables;
import world.bentobox.bentobox.api.panels.PanelItem;
import world.bentobox.bentobox.api.panels.builders.PanelItemBuilder;
import world.bentobox.bentobox.api.user.User;
import world.bentobox.bentobox.database.objects.Island;
import world.bentobox.bentobox.managers.RanksManager;
import world.bentobox.bentobox.util.ItemParser;


public class Flag implements Comparable<Flag> {

    /**
     * Defines the behavior and operation of the flag.
     */
    public enum Type {
        /**
         * Flag protecting an island.
         * It can be modified by the players (island owner).
         * It applies differently depending on the rank of the player who performs the action protected by the flag.
         */
        PROTECTION(Material.SHIELD),
        /**
         * Flag modifying parameters of the island.
         * It can be modified by the players (island owner).
         * This is usually an on/off setting.
         */
        SETTING(Material.COMPARATOR),
        /**
         * Flag applying to the world.
         * It can only be modified by administrators (permission or operator).
         * This is usually an on/off setting.
         */
        WORLD_SETTING(Material.GRASS_BLOCK);

        @NonNull
        private final Material icon;

        Type(@NonNull Material icon) {
            this.icon = icon;
        }

        @NonNull
        public Material getIcon() {
            return icon;
        }
    }

    /**
     * Defines the flag mode
     * @author tastybento
     * @since 1.6.0
     */
    public enum Mode {
        /**
         * Flag should be shown in the basic settings
         */
        BASIC,
        /**
         * Flag should be shown in the advanced settings
         */
        ADVANCED,
        /**
         * Flag should be shown in the expert settings
         */
        EXPERT,
        /**
         * Flag should be shown in the top row if applicable
         */
        TOP_ROW;

        /**
         * Get the next ranking mode above this one. If at the top, it cycles back to the bottom mode
         * @return next ranking mode
         */
        public Mode getNext() {
            return switch (this) {
            case ADVANCED -> EXPERT;
            case BASIC -> ADVANCED;
            default -> BASIC;
            };
        }

        /**
         * Get a list of ranks that are ranked greater than this rank
         * @param rank - rank to compare
         * @return true if ranked greater
         */
        public boolean isGreaterThan(Mode rank) {
            return switch (this) {
            case EXPERT -> rank.equals(BASIC) || rank.equals(ADVANCED);
            case ADVANCED -> rank.equals(BASIC);
            default -> false;
            };
        }
    }

    private static final String PROTECTION_FLAGS = "protection.flags.";

    private final String id;
    private final Material icon;
    private final Listener listener;
    private final Type type;
    private boolean setting;
    private final int defaultRank;
    private final PanelItem.ClickHandler clickHandler;
    private final boolean subPanel;
    private Set<GameModeAddon> gameModes = new HashSet<>();
    private final Addon addon;
    private final int cooldown;
    private final Mode mode;
    private final Set<Flag> subflags;

    private Flag(Builder builder) {
        this.id = builder.id;
        this.icon = builder.icon;
        this.listener = builder.listener;
        this.type = builder.type;
        this.setting = builder.defaultSetting;
        this.defaultRank = builder.defaultRank;
        this.clickHandler = builder.clickHandler;
        this.subPanel = builder.usePanel;
        if (builder.gameModeAddon != null) {
            this.gameModes.add(builder.gameModeAddon);
        }
        this.cooldown = builder.cooldown;
        this.addon = builder.addon;
        this.mode = builder.mode;
        this.subflags = builder.subflags;
    }

    public String getID() {
        return id;
    }

    public Material getIcon() {
        return icon;
    }

    public Optional<Listener> getListener() {
        return Optional.ofNullable(listener);
    }

    /**
     * @return the cooldown
     */
    public int getCooldown() {
        return cooldown;
    }

    /**
     * Check if a setting is set in this world
     * @param world - world
     * @return world setting or default flag setting if a specific world setting is not set.
     * If world is not a game world, then the result will always be false!
     */
    public boolean isSetForWorld(World world) {
        if (!BentoBox.getInstance().getIWM().inWorld(world)) {
            return false;
        }
        WorldSettings ws = BentoBox.getInstance().getIWM().getWorldSettings(world);
        if (type.equals(Type.WORLD_SETTING) || type.equals(Type.PROTECTION)) {
            if (!ws.getWorldFlags().containsKey(getID())) {
                ws.getWorldFlags().put(getID(), setting);
                // Save config file
                BentoBox.getInstance().getIWM().getAddon(world).ifPresent(GameModeAddon::saveWorldSettings);
            }
            return ws.getWorldFlags().get(getID());
        }
        return setting;
    }

    /**
     * Set a world setting
     * @param world - world
     * @param setting - true or false
     */
    public void setSetting(World world, boolean setting) {
        if (getType().equals(Type.WORLD_SETTING) || type.equals(Type.PROTECTION)) {
            BentoBox.getInstance()
            .getIWM()
            .getWorldSettings(world)
            .getWorldFlags()
            .put(getID(), setting);

            // Subflag support
            if (hasSubflags()) {
                subflags.stream()
                .filter(subflag -> subflag.getType().equals(Type.WORLD_SETTING) || subflag.getType().equals(Type.PROTECTION))
                .forEach(subflag -> BentoBox.getInstance()
                        .getIWM()
                        .getWorldSettings(world)
                        .getWorldFlags()
                        .put(subflag.getID(), setting));
            }

            // Save config file
            BentoBox.getInstance().getIWM().getAddon(world).ifPresent(GameModeAddon::saveWorldSettings);
        }
    }

    /**
     * Set the original status of this flag for locations outside of island spaces.
     * May be overridden by the setting for this world.
     * Does not affect subflags.
     * @param defaultSetting - true means it is allowed. false means it is not allowed
     */
    public void setDefaultSetting(boolean defaultSetting) {
        this.setting = defaultSetting;
    }

    /**
     * Set the status of this flag for locations outside of island spaces for a specific world.
     * World must exist and be registered before this method can be called.
     * Does not affect subflags.
     * @param defaultSetting - true means it is allowed. false means it is not allowed
     */
    public void setDefaultSetting(World world, boolean defaultSetting) {
        if (!BentoBox.getInstance().getIWM().inWorld(world)) {
            BentoBox.getInstance().logError("Attempt to set default world setting for unregistered world. Register flags in onEnable.");
            return;
        }
        BentoBox.getInstance().getIWM().getWorldSettings(world).getWorldFlags().put(getID(), defaultSetting);
        // Save config file
        BentoBox.getInstance().getIWM().getAddon(world).ifPresent(GameModeAddon::saveWorldSettings);
    }

    /**
     * @return the type
     */
    public Type getType() {
        return type;
    }

    /**
     * @return the defaultRank
     */
    public int getDefaultRank() {
        return defaultRank;
    }

    /**
     * @return whether the flag uses a subpanel or not
     */
    public boolean hasSubPanel() {
        return subPanel;
    }

    /**
     * Get the addon that made this flag
     * @return the addon
     * @since 1.5.0
     */
    public Addon getAddon() {
        return addon;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((id == null) ? 0 : id.hashCode());
        result = prime * result + ((type == null) ? 0 : type.hashCode());
        return result;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (!(obj instanceof Flag other)) {
            return false;
        }
        if (id == null) {
            if (other.id != null) {
                return false;
            }
        } else if (!id.equals(other.id)) {
            return false;
        }

        return type == other.type;
    }

    /**
     * @return a locale reference for the name of this protection flag
     */
    public String getNameReference() {
        return PROTECTION_FLAGS + this.id + ".name";
    }

    /**
     * @return a locale reference for the icon of this protection flag
     */
    public String getIconReference() {
        return PROTECTION_FLAGS + this.id + ".icon";
    }

    /**
     * @return a locale reference for the description of this protection flag
     */
    public String getDescriptionReference() {
        return PROTECTION_FLAGS + this.id + ".description";
    }

    /**
     * @return a locale reference for the hint of this protection flag
     */
    public String getHintReference() {
        return PROTECTION_FLAGS + this.id + ".hint";
    }

    /**
     * A set of game mode addons that use this flag. If empty, flag applies to all.
     * @return the gameModeAddon
     */
    public Set<GameModeAddon> getGameModes() {
        return gameModes;
    }

    /**
     * @param gameModeAddon the gameModeAddon to set
     */
    public void setGameModes(Set<GameModeAddon> gameModeAddon) {
        this.gameModes = gameModeAddon;
    }

    /**
     * Add a gameModeAddon to this flag
     * @param gameModeAddon - game mode addon
     */
    public void addGameModeAddon(GameModeAddon gameModeAddon) {
        this.gameModes.add(gameModeAddon);
    }

    /**
     * Remove a gameModeAddon to this flag
     * @param gameModeAddon - game mode addon
     * @return <tt>true</tt> if this set contained the specified element
     */
    public boolean removeGameModeAddon(GameModeAddon gameModeAddon) {
        return this.gameModes.remove(gameModeAddon);
    }

    /**
     * Converts a flag to a panel item. The content of the flag will change depending on who the user is and where they are.
     * @param plugin - plugin
     * @param user - user that will see this flag
     * @param world - the world this flag is being shown for. If island is present, then world is the same as the island.
     * @param island - target island, if any
     * @param invisible - true if this flag is not visible to players
     * @return - PanelItem for this flag or null if item is invisible to user
     */
    @Nullable
    public PanelItem toPanelItem(BentoBox plugin, User user, World world, @Nullable Island island, boolean invisible) {
        // Invisibility
        if (!user.isOp() && invisible) {
            return null;
        }
        PanelItemBuilder pib = new PanelItemBuilder()
                .icon(ItemParser.parse(user.getTranslationOrNothing(this.getIconReference()), new ItemStack(icon)))
                .name(user.getTranslation("protection.panel.flag-item.name-layout", TextVariables.NAME,
                        user.getTranslation(getNameReference())))
                .clickHandler(clickHandler)
                .invisible(invisible);
        if (hasSubPanel()) {
            pib.description(user.getTranslation("protection.panel.flag-item.menu-layout", TextVariables.DESCRIPTION, user.getTranslation(getDescriptionReference())));
            return pib.build();
        }

        return switch (getType()) {
        case PROTECTION -> createProtectionFlag(plugin, user, island, pib).build();
        case SETTING -> createSettingFlag(user, island, pib).build();
        case WORLD_SETTING -> createWorldSettingFlag(user, world, pib).build();

        };

    }

    private PanelItemBuilder createWorldSettingFlag(User user, World world, PanelItemBuilder pib) {
        String worldSetting = this.isSetForWorld(world)
                ? user.getTranslation("protection.panel.flag-item.setting-active")
                : user.getTranslation("protection.panel.flag-item.setting-disabled");
        pib.description(user.getTranslation("protection.panel.flag-item.setting-layout", TextVariables.DESCRIPTION, user.getTranslation(getDescriptionReference())
                , "[setting]", worldSetting));
        return pib;
    }

    private PanelItemBuilder createSettingFlag(User user, Island island, PanelItemBuilder pib) {
        if (island != null) {
            String islandSetting = island.isAllowed(this) ? user.getTranslation("protection.panel.flag-item.setting-active")
                    : user.getTranslation("protection.panel.flag-item.setting-disabled");
            pib.description(user.getTranslation("protection.panel.flag-item.setting-layout", TextVariables.DESCRIPTION, user.getTranslation(getDescriptionReference())
                    , "[setting]", islandSetting));
            if (this.cooldown > 0 && island.isCooldown(this)) {
                pib.description(user.getTranslation("protection.panel.flag-item.setting-cooldown"));
            }
        }
        return pib;
    }

    private PanelItemBuilder createProtectionFlag(BentoBox plugin, User user, Island island, PanelItemBuilder pib) {
        if (island != null) {
            int y = island.getFlag(this);
            // Protection flag

            pib.description(user.getTranslation("protection.panel.flag-item.description-layout",
                    TextVariables.DESCRIPTION, user.getTranslation(getDescriptionReference())));

            RanksManager.getInstance().getRanks().forEach((reference, score) -> {

                if (score > RanksManager.BANNED_RANK && score < y) {
                    pib.description(user.getTranslation("protection.panel.flag-item.blocked-rank") + user.getTranslation(reference));
                } else if (score <= RanksManager.OWNER_RANK && score > y) {
                    pib.description(user.getTranslation("protection.panel.flag-item.allowed-rank") + user.getTranslation(reference));
                } else if (score == y) {
                    pib.description(user.getTranslation("protection.panel.flag-item.minimal-rank") + user.getTranslation(reference));
                }
            });
        }

        return pib;
    }


    /**
     * @return the mode
     * @since 1.6.0
     */
    public Mode getMode() {
        return mode;
    }

    /**
     * @return whether the flag has subflags (and therefore is a parent flag)
     * @since 1.17.0
     */
    public boolean hasSubflags() {
        return !subflags.isEmpty();
    }

    /**
     * @return the subflags, an empty Set if none
     * @since 1.17.0
     */
    public Set<Flag> getSubflags() {
        return subflags;
    }

    /**
     * Set the name of this flag for a specified locale. This enables the flag's name to be assigned via API. It will not be stored anywhere
     * and must be rewritten using this call every time the flag is built.
     * @param locale locale (language) to which this name should be assigned. Assigning to {@code Locale.US} will make this the default
     * @param name the translated name for this flag
     * @return true if successful, false if the locale is unknown to this server.
     * See {@link world.bentobox.bentobox.managers.LocalesManager#getAvailableLocales(boolean sort)}
     * @since 1.22.1
     */
    public boolean setTranslatedName(Locale locale, String name) {
        return BentoBox.getInstance().getLocalesManager().setTranslation(locale, getNameReference(), name);
    }

    /**
     * Set the name of this flag for a specified locale. This enables the flag's name to be assigned via API. It will not be stored anywhere
     * and must be rewritten using this call every time the flag is built.
     * @param locale locale (language) to which this name should be assigned. Assigning to {@code Locale.US} will make this the default
     * @param description the translated description for this flag
     * @return true if successful, false if the locale is unknown to this server.
     * See {@link world.bentobox.bentobox.managers.LocalesManager#getAvailableLocales(boolean sort)}
     * @since 1.22.1
     */

    public boolean setTranslatedDescription(Locale locale, String description) {
        return BentoBox.getInstance().getLocalesManager().setTranslation(locale, getDescriptionReference(), description);
    }

    @Override
    public String toString() {
        return "Flag [id=" + id + "]";
    }

    @Override
    public int compareTo(Flag o) {
        return getID().compareTo(o.getID());
    }

    /**
     * Builder for making flags
     * @author tastybento, Poslovitch
     */
    public static class Builder {
        // Mandatory fields
        private final String id;
        private final Material icon;

        // Listener
        private Listener listener;

        // Type - is defaulted to PROTECTION
        private Type type = Type.PROTECTION;

        // Default settings
        private boolean defaultSetting = false;
        private int defaultRank = RanksManager.MEMBER_RANK;

        // ClickHandler - default depends on the type
        private PanelItem.ClickHandler clickHandler;

        // Whether there is a sub-panel or not
        private boolean usePanel = false;

        // GameModeAddon
        private GameModeAddon gameModeAddon;
        private Addon addon;

        // Cooldown
        private int cooldown;

        // Mode
        private Mode mode = Mode.EXPERT;

        // Subflags
        private final Set<Flag> subflags;

        /**
         * Builder for making flags
         * @param id - a unique id that MUST be the same as the enum of the flag
         * @param icon - a material that will be used as the icon in the GUI
         */
        public Builder(String id, Material icon) {
            this.id = id;
            this.icon = icon;
            this.subflags = new HashSet<>();
        }

        /**
         * The listener that should be instantiated to handle events this flag cares about.
         * If the listener class already exists, then do not create it again in another flag.
         * @param listener - Bukkit Listener
         * @return Builder
         */
        public Builder listener(Listener listener) {
            this.listener = listener;
            return this;
        }

        /**
         * The type of flag.
         * @param type {@link Type#PROTECTION}, {@link Type#SETTING} or {@link Type#WORLD_SETTING}
         * @return Builder
         */
        public Builder type(Type type) {
            this.type = type;
            return this;
        }

        /**
         * The click handler to use when this icon is clicked
         * @param clickHandler - click handler
         * @return Builder
         */
        public Builder clickHandler(PanelItem.ClickHandler clickHandler) {
            this.clickHandler = clickHandler;
            return this;
        }

        /**
         * Set the default setting for {@link Type#SETTING} or {@link Type#WORLD_SETTING} flags
         * @param defaultSetting - true or false
         * @return Builder
         */
        public Builder defaultSetting(boolean defaultSetting) {
            this.defaultSetting = defaultSetting;
            return this;
        }

        /**
         * Set the default rank for {@link Type#PROTECTION} flags
         * @param defaultRank - default rank
         * @return Builder
         */
        public Builder defaultRank(int defaultRank) {
            this.defaultRank = defaultRank;
            return this;
        }

        /**
         * Set that this flag icon will open up a sub-panel
         * @param usePanel - true or false
         * @return Builder
         */
        public Builder usePanel(boolean usePanel) {
            this.usePanel = usePanel;
            return this;
        }

        /**
         * Make this flag specific to this gameMode
         * @param gameModeAddon game mode addon
         * @return Builder
         */
        public Builder setGameMode(GameModeAddon gameModeAddon) {
            this.gameModeAddon = gameModeAddon;
            return this;
        }

        /**
         * The addon registering this flag. Ensure this is set to enable the addon to be reloaded.
         * @param addon addon
         * @return Builder
         * @since 1.5.0
         */
        public Builder addon(Addon addon) {
            this.addon = addon;
            return this;
        }

        /**
         * Set a cooldown for {@link Type#SETTING} flag. Only applicable for settings.
         * @param cooldown in seconds
         * @return Builder
         * @since 1.6.0
         */
        public Builder cooldown(int cooldown) {
            this.cooldown = cooldown;
            return this;
        }

        /**
         * Set the flag difficulty mode.
         * Defaults to {@link Flag.Mode#EXPERT}.
         * @param mode - difficulty mode
         * @return Builder - flag builder
         * @since 1.6.0
         */
        public Builder mode(Mode mode) {
            this.mode = mode;
            return this;
        }

        /**
         * Add subflags and designate this flag as a parent flag.
         * Subflags have their state simultaneously changed with the parent flag.
         * Take extra care to ensure that subflags have the same number of possible values as the parent flag.
         * @param flags all Flags that are subflags
         * @return Builder - flag builder
         * @since 1.17.1
         */
        public Builder subflags(Flag... flags) {
            this.subflags.addAll(Arrays.asList(flags));
            return this;
        }

        /**
         * Build the flag
         * @return Flag
         */
        public Flag build() {
            // If no clickHandler has been set, then apply default ones
            if (clickHandler == null) {
                clickHandler = switch (type) {
                case SETTING -> new IslandToggleClick(id);
                case WORLD_SETTING -> new WorldToggleClick(id);
                default -> new CycleClick(id);
                };
            }

            return new Flag(this);
        }
    }

}
