package net.ludovicoflaviano.smartspawner;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;

public final class StateStore {
    private final SmartSpawnerPlugin plugin;
    private final File file;
    private final Map<String, SpawnerState> states = new HashMap<>();
    public StateStore(SmartSpawnerPlugin plugin) { this.plugin = plugin; this.file = new File(plugin.getDataFolder(), "spawners.yml"); load(); }
    private String key(Location l) { return l.getWorld().getUID() + ":" + l.getBlockX() + ":" + l.getBlockY() + ":" + l.getBlockZ(); }
    public SpawnerState get(Location location) { return states.computeIfAbsent(key(location), k -> new SpawnerState()); }
    public void put(Location location, SpawnerState state) { states.put(key(location), state); save(); }
    public void remove(Location location) { states.remove(key(location)); save(); }
    public Set<Location> locations() { Set<Location> result = new HashSet<>(); for (String raw : states.keySet()) { String[] p = raw.split(":"); if (p.length != 4) continue; try { World world = Bukkit.getWorld(UUID.fromString(p[0])); if (world != null) result.add(new Location(world, Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]))); } catch (Exception ignored) {} } return result; }
    public void load() { states.clear(); if (!file.exists()) return; try { YamlConfiguration y = YamlConfiguration.loadConfiguration(file); for (String k : y.getKeys(false)) { String raw = y.getString(k); if (raw != null) states.put(k, SpawnerState.deserialize(raw)); } } catch (Exception e) { plugin.getLogger().warning("Could not load spawners.yml: " + e.getMessage()); } }
    public void save() { YamlConfiguration y = new YamlConfiguration(); states.forEach(y::set); try { file.getParentFile().mkdirs(); y.save(file); } catch (IOException e) { plugin.getLogger().warning("Could not save spawners.yml: " + e.getMessage()); } }
}
