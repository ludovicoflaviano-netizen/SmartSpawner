package net.ludovicoflaviano.smartspawner;

import net.milkbowl.vault.economy.Economy;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.command.*;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.loot.LootContext;
import org.bukkit.loot.LootTable;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public final class SmartSpawnerPlugin extends JavaPlugin implements Listener, TabExecutor {
    private static final String TITLE = "sᴍᴀʀᴛ sᴘᴀᴡɴᴇʀ";
    private static final String UPGRADES = "ᴜᴘɢʀᴀᴅᴇs";
    private static final String STORAGE = "sᴛᴏʀᴀɢᴇ";
    private static final String PRODUCTION = "ᴘʀᴏᴅᴜᴄᴛɪᴏɴ";
    private static final String EFFICIENCY = "ᴇғғɪᴄɪᴇɴᴄʏ";
    private static final int MAX_BRANCH = 3;

    private StateStore store;
    private Economy economy;
    private NamespacedKey stateKey;
    private NamespacedKey markerKey;
    private final Map<UUID, MenuSession> sessions = new HashMap<>();
    private final Map<String, Long> nextProduction = new HashMap<>();
    private final Set<Location> active = new HashSet<>();
    private final Set<UUID> switching = new HashSet<>();

    @Override public void onEnable() {
        saveDefaultConfig();
        stateKey = new NamespacedKey(this, "state");
        markerKey = new NamespacedKey(this, "smart_spawner");
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) { getLogger().severe("Vault economy provider not found."); getServer().getPluginManager().disablePlugin(this); return; }
        economy = rsp.getProvider();
        store = new StateStore(this);
        active.addAll(store.locations());
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("smartspawner")).setExecutor(this);
        Objects.requireNonNull(getCommand("smartspawner")).setTabCompleter(this);
        new BukkitRunnable() { @Override public void run() { tickProduction(); } }.runTaskTimer(this, 20L, 20L);
        getLogger().info("SmartSpawner enabled.");
    }

    @Override public void onDisable() { if (store != null) store.save(); }

    private void tickProduction() {
        if (store == null) return;
        long now = System.currentTimeMillis();
        for (Location loc : new HashSet<>(active)) {
            if (!loc.getChunk().isLoaded() || loc.getBlock().getType() != Material.SPAWNER) continue;
            CreatureSpawner cs = (CreatureSpawner) loc.getBlock().getState();
            EntityType detected = cs.getSpawnedType();
            if (detected == null || !detected.isAlive()) continue;
            SpawnerState s = store.get(loc);
            if (s.type() != detected) { s.type(detected); store.put(loc, s); }
            String key = s.key(loc);
            if (now < nextProduction.getOrDefault(key, 0L)) continue;
            produce(loc, s);
            nextProduction.put(key, now + intervalMillis(s));
            store.put(loc, s);
        }
    }

    private long intervalMillis(SpawnerState s) { return switch (s.productionLevel()) { case 2 -> 40_000L; case 3 -> 25_000L; default -> 60_000L; }; }
    private int productionMultiplier(SpawnerState s) { return switch (s.productionLevel()) { case 2 -> 2; case 3 -> 3; default -> 1; }; }
    private int xpPerCycle(SpawnerState s) { return switch (s.efficiencyLevel()) { case 2 -> 12; case 3 -> 24; default -> 6; }; }

    private void produce(Location loc, SpawnerState s) {
        EntityType type = ((CreatureSpawner) loc.getBlock().getState()).getSpawnedType();
        if (type == null) return;
        Collection<ItemStack> generated = generateLoot(loc, type);
        int multiplier = productionMultiplier(s);
        for (ItemStack base : generated) {
            if (base == null || base.getType().isAir()) continue;
            for (int i = 0; i < multiplier; i++) addToStorage(s, base.clone());
        }
        s.addXp(xpPerCycle(s) * multiplier);
    }

    private Collection<ItemStack> generateLoot(Location loc, EntityType type) {
        LootTable table = Bukkit.getLootTable(NamespacedKey.minecraft("entities/" + type.name().toLowerCase(Locale.ROOT)));
        if (table != null) {
            try { return table.populateLoot(new Random(), new LootContext.Builder(loc).luck(0f).build()); }
            catch (Exception ignored) {}
        }
        return fallbackLoot(type);
    }

    private Collection<ItemStack> fallbackLoot(EntityType type) {
        Material material = switch (type) {
            case ZOMBIE, HUSK, DROWNED, ZOMBIE_VILLAGER -> Material.ROTTEN_FLESH;
            case SKELETON, STRAY, WITHER_SKELETON -> Material.BONE;
            case CREEPER -> Material.GUNPOWDER;
            case SPIDER, CAVE_SPIDER -> Material.STRING;
            case BLAZE -> Material.BLAZE_ROD;
            case ENDERMAN -> Material.ENDER_PEARL;
            case SLIME -> Material.SLIME_BALL;
            case MAGMA_CUBE -> Material.MAGMA_CREAM;
            case COW -> Material.BEEF;
            case PIG -> Material.PORKCHOP;
            case CHICKEN -> Material.CHICKEN;
            case SHEEP -> Material.MUTTON;
            case RABBIT -> Material.RABBIT;
            case COD -> Material.COD;
            case SALMON -> Material.SALMON;
            default -> Material.ROTTEN_FLESH;
        };
        return List.of(new ItemStack(material));
    }

    private int capacityStacks(SpawnerState s) { return 45 * s.storageLevel(); }

    private void addToStorage(SpawnerState s, ItemStack add) {
        if (add == null || add.getAmount() <= 0) return;
        List<ItemStack> storage = s.drops();
        for (ItemStack existing : storage) {
            if (existing.isSimilar(add) && existing.getAmount() < existing.getMaxStackSize()) {
                int move = Math.min(existing.getMaxStackSize() - existing.getAmount(), add.getAmount());
                existing.setAmount(existing.getAmount() + move);
                add.setAmount(add.getAmount() - move);
                if (add.getAmount() <= 0) return;
            }
        }
        int capacity = capacityStacks(s);
        while (add.getAmount() > 0 && storage.size() < capacity) {
            int move = Math.min(add.getMaxStackSize(), add.getAmount());
            ItemStack part = add.clone(); part.setAmount(move); storage.add(part); add.setAmount(add.getAmount() - move);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRightClick(PlayerInteractEvent e) {
        if (!e.getAction().isRightClick() || e.getClickedBlock() == null || e.getClickedBlock().getType() != Material.SPAWNER) return;
        CreatureSpawner cs = (CreatureSpawner) e.getClickedBlock().getState();
        EntityType type = cs.getSpawnedType();
        if (type == null) return;
        e.setCancelled(true);
        Location loc = e.getClickedBlock().getLocation();
        SpawnerState s = store.get(loc);
        if (s.type() != type) { s.type(type); store.put(loc, s); }
        active.add(loc.clone());
        openMain(e.getPlayer(), loc, 0);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (e.getBlockPlaced().getType() != Material.SPAWNER) return;
        Location loc = e.getBlockPlaced().getLocation();
        CreatureSpawner cs = (CreatureSpawner) e.getBlockPlaced().getState();
        SpawnerState decoded = readItemState(e.getItemInHand());
        if (decoded != null) {
            cs.setSpawnedType(decoded.type());
            cs.update(true, false);
            store.put(loc, decoded);
        } else {
            SpawnerState fresh = new SpawnerState();
            EntityType detected = cs.getSpawnedType();
            if (detected != null) fresh.type(detected);
            store.put(loc, fresh);
        }
        active.add(loc.clone());
        nextProduction.remove(store.get(loc).key(loc));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(BlockDropItemEvent e) {
        if (e.getBlockState().getType() != Material.SPAWNER) return;
        Location loc = e.getBlockState().getLocation();
        SpawnerState s = store.get(loc);
        if (e.getBlockState() instanceof CreatureSpawner cs && cs.getSpawnedType() != null) s.type(cs.getSpawnedType());
        for (Item item : e.getItems()) if (item.getItemStack().getType() == Material.SPAWNER) item.setItemStack(createSpawnerItem(s));
        store.remove(loc);
        active.remove(loc);
        nextProduction.remove(s.key(loc));
    }

    @EventHandler public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof SpawnerMenuHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        MenuSession session = sessions.get(p.getUniqueId());
        if (session == null) return;
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= e.getView().getTopInventory().getSize()) return;
        if (session.page == Screen.STORAGE) {
            if (slot < 45) {
                ItemStack clicked = e.getCurrentItem();
                if (clicked != null && !clicked.getType().isAir() && e.isShiftClick()) {
                    p.getInventory().addItem(clicked.clone());
                    e.getView().getTopInventory().setItem(slot, null);
                    saveGui(p, session);
                }
            } else if (slot == 45 && session.pageNumber > 0) { saveGui(p, session); openMain(p, session.loc, session.pageNumber - 1); }
            else if (slot == 47) openUpgrades(p, session.loc);
            else if (slot == 49) collectXp(p, session.loc);
            else if (slot == 51) openInfo(p, session.loc);
            else if (slot == 52 && session.pageNumber + 1 < pageCount(store.get(session.loc))) { saveGui(p, session); openMain(p, session.loc, session.pageNumber + 1); }
            else if (slot == 53) { saveGui(p, session); p.closeInventory(); }
        } else if (session.page == Screen.UPGRADES) {
            if (slot == 20) buyBranch(p, session.loc, Branch.PRODUCTION);
            else if (slot == 22) buyBranch(p, session.loc, Branch.STORAGE);
            else if (slot == 24) buyBranch(p, session.loc, Branch.EFFICIENCY);
            else if (slot == 45) openMain(p, session.loc, 0);
        } else if (slot == 45) openMain(p, session.loc, 0);
    }

    @EventHandler public void onDrag(InventoryDragEvent e) { if (e.getView().getTopInventory().getHolder() instanceof SpawnerMenuHolder) e.setCancelled(true); }

    @EventHandler public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
        MenuSession s = sessions.remove(e.getPlayer().getUniqueId());
        if (s != null) saveGui(e.getPlayer(), s);
        switching.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler public void onClose(InventoryCloseEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof SpawnerMenuHolder)) return;
        if (!(e.getPlayer() instanceof Player p)) return;
        if (switching.remove(p.getUniqueId())) return;
        MenuSession s = sessions.remove(p.getUniqueId());
        if (s != null) saveGui(p, s);
    }

    private void openMain(Player p, Location loc, int page) {
        SpawnerState s = store.get(loc);
        Inventory inv = Bukkit.createInventory(new SpawnerMenuHolder(loc, Screen.STORAGE), 54, Component.text(TITLE + " • " + pretty(s.type())));
        ((SpawnerMenuHolder) inv.getHolder()).inventory = inv;
        int totalPages = pageCount(s), safePage = Math.max(0, Math.min(page, totalPages - 1));
        int start = safePage * 45;
        for (int i = 0; i < 45 && start + i < s.drops().size(); i++) if (s.drops().get(start + i) != null) inv.setItem(i, s.drops().get(start + i).clone());
        inv.setItem(45, button(Material.ARROW, "ᴘʀᴇᴠɪᴏᴜs", safePage == 0 ? "ᴍᴀx ᴘᴀɢᴇ" : "ᴘᴀɢᴇ " + safePage));
        inv.setItem(46, button(Material.EXPERIENCE_BOTTLE, "ᴇxᴘ", "stored: " + format(s.storedXp())));
        inv.setItem(47, button(Material.SMITHING_TABLE, UPGRADES, "production " + level(s.productionLevel()), "storage " + level(s.storageLevel()), "efficiency " + level(s.efficiencyLevel())));
        inv.setItem(49, button(Material.LIME_DYE, "ᴄᴏʟʟᴇᴄᴛ", "take stored xp"));
        inv.setItem(51, button(iconFor(s.type()), "ɪɴғᴏ", "ᴍᴏʙ: " + pretty(s.type()), "ᴘʀᴏᴅᴜᴄᴛɪᴏɴ: " + intervalText(s), "sᴛᴏʀᴀɢᴇ: " + s.drops().size() + "/" + capacityStacks(s)));
        inv.setItem(52, button(Material.ARROW, "ɴᴇxᴛ", safePage + 1 >= totalPages ? "ᴍᴀx ᴘᴀɢᴇ" : "ᴘᴀɢᴇ " + (safePage + 2)));
        inv.setItem(53, button(Material.BARRIER, "ᴄʟᴏsᴇ"));
        sessions.put(p.getUniqueId(), new MenuSession(loc.clone(), Screen.STORAGE, safePage));
        switching.add(p.getUniqueId());
        p.openInventory(inv);
    }

    private void openUpgrades(Player p, Location loc) {
        SpawnerState s = store.get(loc);
        Inventory inv = Bukkit.createInventory(new SpawnerMenuHolder(loc, Screen.UPGRADES), 54, Component.text(UPGRADES));
        ((SpawnerMenuHolder) inv.getHolder()).inventory = inv;
        fillBorder(inv);
        inv.setItem(20, branchButton(Material.REDSTONE, PRODUCTION, s.productionLevel(), branchCost(s.productionLevel()), "faster cycles", "more drops"));
        inv.setItem(22, branchButton(Material.CHEST, STORAGE, s.storageLevel(), branchCostStorage(s.storageLevel()), "more virtual slots", "keeps overflow safe"));
        inv.setItem(24, branchButton(Material.GOLDEN_APPLE, EFFICIENCY, s.efficiencyLevel(), branchCostEfficiency(s.efficiencyLevel()), "more xp per cycle"));
        inv.setItem(45, button(Material.ARROW, "ʙᴀᴄᴋ"));
        sessions.put(p.getUniqueId(), new MenuSession(loc.clone(), Screen.UPGRADES, 0));
        switching.add(p.getUniqueId());
        p.openInventory(inv);
    }

    private void openInfo(Player p, Location loc) {
        SpawnerState s = store.get(loc);
        Inventory inv = Bukkit.createInventory(new SpawnerMenuHolder(loc, Screen.INFO), 54, Component.text("sᴘᴀᴡɴᴇʀ ɪɴғᴏ"));
        ((SpawnerMenuHolder) inv.getHolder()).inventory = inv;
        fillBorder(inv);
        inv.setItem(22, button(iconFor(s.type()), pretty(s.type()), "ᴘʀᴏᴅᴜᴄᴛɪᴏɴ: " + intervalText(s), "ᴅʀᴏᴘs: x" + productionMultiplier(s), "xᴘ: +" + xpPerCycle(s) + "/cycle", "sᴛᴏʀᴀɢᴇ: " + s.drops().size() + "/" + capacityStacks(s)));
        inv.setItem(45, button(Material.ARROW, "ʙᴀᴄᴋ"));
        sessions.put(p.getUniqueId(), new MenuSession(loc.clone(), Screen.INFO, 0));
        switching.add(p.getUniqueId());
        p.openInventory(inv);
    }

    private void buyBranch(Player p, Location loc, Branch branch) {
        SpawnerState s = store.get(loc);
        int current = s.level(branch);
        if (current >= MAX_BRANCH) { p.sendMessage(Component.text("ᴛʜɪs ʙʀᴀɴᴄʜ ɪs ᴍᴀxᴇᴅ.", NamedTextColor.YELLOW)); return; }
        double cost = switch (branch) { case PRODUCTION -> branchCost(current); case STORAGE -> branchCostStorage(current); case EFFICIENCY -> branchCostEfficiency(current); };
        if (!economy.has(p, cost)) { p.sendMessage(Component.text("ɴᴇᴇᴅ $" + String.format(Locale.US, "%,.0f", cost), NamedTextColor.RED)); return; }
        economy.withdrawPlayer(p, cost);
        s.level(branch, current + 1);
        store.put(loc, s);
        openUpgrades(p, loc);
    }

    private double branchCost(int level) { return level == 1 ? 1_000_000D : 10_000_000D; }
    private double branchCostStorage(int level) { return level == 1 ? 2_500_000D : 25_000_000D; }
    private double branchCostEfficiency(int level) { return level == 1 ? 5_000_000D : 50_000_000D; }

    private void collectXp(Player p, Location loc) {
        SpawnerState s = store.get(loc);
        int amount = (int)Math.min(Integer.MAX_VALUE, Math.floor(s.storedXp()));
        if (amount <= 0) { p.sendMessage(Component.text("ɴᴏ sᴛᴏʀᴇᴅ xᴘ.", NamedTextColor.GRAY)); return; }
        p.giveExp(amount);
        s.addXp(-amount);
        store.put(loc, s);
        openMain(p, loc, 0);
    }

    private void saveGui(Player p, MenuSession session) {
        SpawnerState s = store.get(session.loc);
        if (session.page == Screen.STORAGE) {
            int start = session.pageNumber * 45;
            while (s.drops().size() < start) s.drops().add(null);
            for (int i = 0; i < 45 && start + i < s.drops().size(); i++) {
                ItemStack item = p.getOpenInventory().getTopInventory().getItem(i);
                s.drops().set(start + i, item == null || item.getType().isAir() ? null : item.clone());
            }
            s.drops().removeIf(Objects::isNull);
        }
        store.put(session.loc, s);
    }

    private int pageCount(SpawnerState s) { return Math.max(1, (s.drops().size() + 44) / 45); }

    private ItemStack createSpawnerItem(SpawnerState s) {
        ItemStack item = new ItemStack(Material.SPAWNER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(TITLE + " • " + pretty(s.type())));
        meta.lore(List.of(Component.text("ᴘʀᴏᴅᴜᴄᴛɪᴏɴ " + s.productionLevel() + "/3"), Component.text("sᴛᴏʀᴀɢᴇ " + s.storageLevel() + "/3"), Component.text("ᴇғғɪᴄɪᴇɴᴄʏ " + s.efficiencyLevel() + "/3"), Component.text("ᴠɪʀᴛᴜᴀʟ xᴘ: " + format(s.storedXp()))));
        meta.getPersistentDataContainer().set(stateKey, PersistentDataType.STRING, s.serialize());
        meta.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte)1);
        item.setItemMeta(meta);
        return item;
    }

    private SpawnerState readItemState(ItemStack item) {
        if (item == null || item.getType() != Material.SPAWNER || !item.hasItemMeta()) return null;
        String raw = item.getItemMeta().getPersistentDataContainer().get(stateKey, PersistentDataType.STRING);
        return raw == null ? null : SpawnerState.deserialize(raw);
    }

    private ItemStack button(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name));
        List<Component> lines = new ArrayList<>();
        for (String line : lore) lines.add(Component.text(line));
        meta.lore(lines);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack branchButton(Material material, String name, int level, double cost, String... effects) {
        if (level >= MAX_BRANCH) return button(material, name, "ʟᴇᴠᴇʟ 3/3", "ᴍᴀxᴇᴅ");
        List<String> lore = new ArrayList<>();
        lore.add("ʟᴇᴠᴇʟ " + level + "/3");
        lore.add("ɴᴇxᴛ: " + (level + 1) + "/3");
        lore.add("ᴄᴏsᴛ: $" + String.format(Locale.US, "%,.0f", cost));
        Collections.addAll(lore, effects);
        lore.add("ᴄʟɪᴄᴋ ᴛᴏ ᴜᴘɢʀᴀᴅᴇ");
        return button(material, name, lore.toArray(String[]::new));
    }

    private void fillBorder(Inventory inv) {
        ItemStack filler = button(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 54; i++) if (i < 9 || i >= 45 || i % 9 == 0 || i % 9 == 8) inv.setItem(i, filler);
    }

    private Material iconFor(EntityType type) { return switch (type) { case ZOMBIE, HUSK, DROWNED, ZOMBIE_VILLAGER -> Material.ROTTEN_FLESH; case SKELETON, STRAY, WITHER_SKELETON -> Material.BONE; case CREEPER -> Material.GUNPOWDER; case SPIDER, CAVE_SPIDER -> Material.STRING; case BLAZE -> Material.BLAZE_ROD; case ENDERMAN -> Material.ENDER_PEARL; default -> Material.SPAWNER; }; }
    private String pretty(EntityType type) { return type.name().toLowerCase(Locale.ROOT).replace('_', ' '); }
    private String level(int n) { return n + "/3"; }
    private String format(double n) { return String.format(Locale.US, "%,.0f", n); }
    private String intervalText(SpawnerState s) { return (intervalMillis(s) / 1000) + "s"; }

    enum Branch { PRODUCTION, STORAGE, EFFICIENCY }
    private enum Screen { STORAGE, UPGRADES, INFO }
    private record MenuSession(Location loc, Screen page, int pageNumber) {}
    private static final class SpawnerMenuHolder implements InventoryHolder {
        private final Location location; private final Screen screen; private Inventory inventory;
        private SpawnerMenuHolder(Location location, Screen screen) { this.location = location; this.screen = screen; }
        @Override public Inventory getInventory() { return inventory; }
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) return true;
        if (!p.hasPermission("smartspawner.admin")) { p.sendMessage(Component.text("No permission.", NamedTextColor.RED)); return true; }
        if (args.length >= 3 && args[0].equalsIgnoreCase("give")) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) { p.sendMessage(Component.text("Player not found.", NamedTextColor.RED)); return true; }
            EntityType type; try { type = EntityType.valueOf(args[2].toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException ex) { p.sendMessage(Component.text("Unknown entity.", NamedTextColor.RED)); return true; }
            int amount = 1; if (args.length >= 4) try { amount = Math.max(1, Integer.parseInt(args[3])); } catch (NumberFormatException ignored) {}
            SpawnerState s = new SpawnerState(); s.type(type); ItemStack base = createSpawnerItem(s);
            while (amount > 0) { int take = Math.min(base.getMaxStackSize(), amount); ItemStack part = base.clone(); part.setAmount(take); target.getInventory().addItem(part); amount -= take; }
            p.sendMessage(Component.text("Given smart spawner.", NamedTextColor.GREEN)); return true;
        }
        p.sendMessage(Component.text("/smartspawner give <player> <entity> [amount]", NamedTextColor.GRAY)); return true;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("give");
        if (args.length == 2) return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        if (args.length == 3) { String q = args[2].toUpperCase(Locale.ROOT); return Arrays.stream(EntityType.values()).filter(EntityType::isAlive).map(Enum::name).filter(n -> n.startsWith(q)).limit(50).toList(); }
        return List.of();
    }
}
