package com.idlearc.companion;

/** Which additional endpoints actually feed each visible tab. Dashboard is shared. */
public final class LiveSections {
    public static final int INVENTORY = 0, GEMS = 1, BOSS = 2, GUILD = 3, COUNT = 4;
    private LiveSections() {}
    public static int maskForTab(int tab) {
        switch (tab) {
            case 0: return (1 << BOSS) | (1 << GUILD); // radar
            case 3: return (1 << INVENTORY) | (1 << GEMS); // bag
            case 4: case 5: return 1 << GEMS; // gems, pet advisor
            default: return 0; // other tabs use dashboard
        }
    }
    public static String label(int resource) {
        switch (resource) {
            case INVENTORY: return "сумка";
            case GEMS: return "камни";
            case BOSS: return "босс";
            case GUILD: return "гильдия";
            default: return "раздел";
        }
    }
    public static String path(int resource) {
        switch (resource) {
            case INVENTORY: return "/api/inventory";
            case GEMS: return "/api/gems";
            case BOSS: return "/api/boss-fight/status";
            case GUILD: return "/api/guild";
            default: throw new IllegalArgumentException("Неизвестный раздел");
        }
    }
}
