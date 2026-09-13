package net.ludovicoflaviano.smartspawner;

import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SpawnerState {
    private int productionLevel = 1;
    private int storageLevel = 1;
    private int efficiencyLevel = 1;
    private double storedXp;
    private EntityType type = EntityType.ZOMBIE;
    private final List<ItemStack> drops = new ArrayList<>();

    public int productionLevel() { return productionLevel; }
    public int storageLevel() { return storageLevel; }
    public int efficiencyLevel() { return efficiencyLevel; }
    public double storedXp() { return storedXp; }
    public EntityType type() { return type; }
    public List<ItemStack> drops() { return drops; }
    public void productionLevel(int level) { productionLevel = clamp(level); }
    public void storageLevel(int level) { storageLevel = clamp(level); }
    public void efficiencyLevel(int level) { efficiencyLevel = clamp(level); }
    public void addXp(double amount) { storedXp = Math.max(0D, storedXp + amount); }
    public void type(EntityType type) { if (type != null) this.type = type; }
    public int level(SmartSpawnerPlugin.Branch branch) { return switch (branch) { case PRODUCTION -> productionLevel; case STORAGE -> storageLevel; case EFFICIENCY -> efficiencyLevel; }; }
    public void level(SmartSpawnerPlugin.Branch branch, int level) { switch (branch) { case PRODUCTION -> productionLevel(level); case STORAGE -> storageLevel(level); case EFFICIENCY -> efficiencyLevel(level); } }
    public String key(Location l) { return l.getWorld().getUID() + ":" + l.getBlockX() + ":" + l.getBlockY() + ":" + l.getBlockZ(); }
    public String serialize() { YamlConfiguration y = new YamlConfiguration(); y.set("production", productionLevel); y.set("storage", storageLevel); y.set("efficiency", efficiencyLevel); y.set("xp", storedXp); y.set("type", type.name()); y.set("drops", new ArrayList<>(drops)); return y.saveToString(); }
    public static SpawnerState deserialize(String text) { SpawnerState s = new SpawnerState(); try { YamlConfiguration y = new YamlConfiguration(); y.loadFromString(text); s.productionLevel(y.getInt("production", y.getInt("level", 1))); s.storageLevel(y.getInt("storage", y.getInt("level", 1))); s.efficiencyLevel(y.getInt("efficiency", y.getInt("level", 1))); s.storedXp = Math.max(0D, y.getDouble("xp", 0D)); try { s.type(EntityType.valueOf(y.getString("type", "ZOMBIE").toUpperCase(Locale.ROOT))); } catch (IllegalArgumentException ignored) {} List<?> list = y.getList("drops"); if (list != null) for (Object obj : list) if (obj instanceof ItemStack item && !item.getType().isAir()) s.drops.add(item.clone()); } catch (Exception ignored) {} return s; }
    private int clamp(int level) { return Math.max(1, Math.min(3, level)); }
}
