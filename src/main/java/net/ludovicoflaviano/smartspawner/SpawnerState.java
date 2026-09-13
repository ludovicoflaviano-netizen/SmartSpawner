package net.ludovicoflaviano.smartspawner;

import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.List;

public final class SpawnerState {
    private int level = 1;
    private double storedXp = 0.0;
    private EntityType type = EntityType.ZOMBIE;
    private final List<ItemStack> drops = new ArrayList<>();

    public int level() { return level; }
    public void level(int level) { this.level = Math.max(1, Math.min(3, level)); }
    public double storedXp() { return storedXp; }
    public void storedXp(double xp) { this.storedXp = Math.max(0, xp); }
    public EntityType type() { return type; }
    public void type(EntityType type) { if (type != null) this.type = type; }
    public List<ItemStack> drops() { return drops; }

    public String serialize() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("level", level);
        y.set("xp", storedXp);
        y.set("type", type.name());
        y.set("drops", new ArrayList<>(drops));
        return y.saveToString();
    }

    public static SpawnerState deserialize(String text) {
        SpawnerState s = new SpawnerState();
        try {
            YamlConfiguration y = new YamlConfiguration();
            y.loadFromString(text);
            s.level(y.getInt("level", 1));
            s.storedXp(y.getDouble("xp", 0));
            try { s.type(EntityType.valueOf(y.getString("type", "ZOMBIE").toUpperCase())); } catch (Exception ignored) {}
            List<?> list = y.getList("drops");
            if (list != null) for (Object o : list) if (o instanceof ItemStack item && !item.getType().isAir()) s.drops.add(item);
        } catch (Exception ignored) {}
        return s;
    }

    public String key(Location l) {
        return l.getWorld().getUID() + ":" + l.getBlockX() + ":" + l.getBlockY() + ":" + l.getBlockZ();
    }
}
