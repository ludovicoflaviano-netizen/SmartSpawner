package net.ludovicoflaviano.smartspawner;

import net.milkbowl.vault.economy.Economy;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.loot.LootContext;
import org.bukkit.loot.LootTable;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;

public final class SmartSpawnerPlugin extends JavaPlugin implements Listener, TabExecutor {
    public static final String TITLE = "sᴍᴀʀᴛ sᴘᴀᴡɴᴇʀ";
    private StateStore store;
    private Economy economy;
    private NamespacedKey itemStateKey;
    private NamespacedKey itemMarkerKey;
    private final Map<UUID, Location> openSpawners = new HashMap<>();

    @Override public void onEnable() {
        saveDefaultConfig();
        itemStateKey = new NamespacedKey(this, "state");
        itemMarkerKey = new NamespacedKey(this, "smart_spawner");
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp != null) economy = rsp.getProvider();
        if (economy == null) { getLogger().severe("Vault economy provider was not found. SmartSpawner is disabled."); return; }
        store = new StateStore(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("smartspawner")).setExecutor(this);
        Objects.requireNonNull(getCommand("smartspawner")).setTabCompleter(this);
        startProductionTask();
        getLogger().info("SmartSpawner enabled for Paper 1.21.11.");
    }

    @Override public void onDisable() { if (store != null) store.save(); }

    private void startProductionTask() {
        new BukkitRunnable() {
            @Override public void run() {
                if (store == null) return;
                for (World world : Bukkit.getWorlds()) {
                    // State is location keyed; production is checked by currently loaded spawner blocks.
                    for (Chunk chunk : world.getLoadedChunks()) {
                        for (int cx = 0; cx < 16; cx++) for (int cz = 0; cz < 16; cz++) {
                            // Intentionally no full block scan: state locations are processed through the lightweight state map below.
                        }
                    }
                }
                tickKnownSpawners();
            }
        }.runTaskTimer(this, 20L, 20L);
    }

    private final Map<String, Long> nextProduction = new HashMap<>();

    private void tickKnownSpawners() {
        // Production is driven by spawners that have been opened/placed during this runtime.
        // Persisted spawners resume when opened; this avoids scanning every block in every loaded chunk.
        for (Location loc : new ArrayList<>(activeLocations())) {
            if (!loc.getChunk().isLoaded() || loc.getBlock().getType() != Material.SPAWNER) { nextProduction.remove(key(loc)); continue; }
            SpawnerState state = store.get(loc);
            long now = System.currentTimeMillis();
            long interval = intervalSeconds(state.level()) * 1000L;
            if (now < nextProduction.getOrDefault(key(loc), 0L)) continue;
            produce(loc, state);
            nextProduction.put(key(loc), now + interval);
            store.put(loc, state);
        }
    }

    private final Set<Location> active = new HashSet<>();
    private Set<Location> activeLocations() { return new HashSet<>(active); }
    private String key(Location l) { return l.getWorld().getUID()+":"+l.getBlockX()+":"+l.getBlockY()+":"+l.getBlockZ(); }
    private long intervalSeconds(int level) { return getConfig().getLong("settings.production.level-"+level+"-seconds", level==1?60:level==2?40:25); }
    private int multiplier(int level) { return getConfig().getInt("settings.production.level-"+level+"-multiplier", level); }
    private int xpPerCycle(int level) { return getConfig().getInt("settings.production.level-"+level+"-xp", level*5); }

    private void produce(Location loc, SpawnerState state) {
        EntityType type = state.type();
        if (loc.getBlock().getState() instanceof CreatureSpawner cs && cs.getSpawnedType() != null) { type = cs.getSpawnedType(); state.type(type); }
        Collection<ItemStack> generated = generateLoot(loc, type);
        int mult = multiplier(state.level());
        for (ItemStack base : generated) {
            if (base == null || base.getType().isAir()) continue;
            ItemStack item = base.clone();
            item.setAmount(Math.min(item.getMaxStackSize(), item.getAmount() * mult));
            addToStorage(state.drops(), item, getConfig().getInt("settings.storage-slots", 45));
        }
        state.storedXp(state.storedXp() + xpPerCycle(state.level()) * mult);
    }

    private Collection<ItemStack> generateLoot(Location loc, EntityType type) {
        LootTable table = Bukkit.getLootTable(NamespacedKey.minecraft("entities/" + type.name().toLowerCase(Locale.ROOT)));
        if (table != null) {
            try { return table.populateLoot(new Random(), new LootContext.Builder(loc).luck(0).build()); }
            catch (Exception ignored) {}
        }
        return fallbackLoot(type);
    }

    private Collection<ItemStack> fallbackLoot(EntityType type) {
        Material m = switch (type) {
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
        return List.of(new ItemStack(m, 1));
    }

    private void addToStorage(List<ItemStack> storage, ItemStack add, int maxStacks) {
        for (ItemStack existing : storage) {
            if (existing.isSimilar(add) && existing.getAmount() < existing.getMaxStackSize()) {
                int room = existing.getMaxStackSize() - existing.getAmount();
                int moved = Math.min(room, add.getAmount());
                existing.setAmount(existing.getAmount() + moved);
                add.setAmount(add.getAmount() - moved);
                if (add.getAmount() <= 0) return;
            }
        }
        if (storage.size() >= maxStacks || add.getAmount() <= 0) return;
        while (add.getAmount() > 0 && storage.size() < maxStacks) {
            int take = Math.min(add.getMaxStackSize(), add.getAmount());
            ItemStack part = add.clone(); part.setAmount(take); storage.add(part); add.setAmount(add.getAmount()-take);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRightClick(PlayerInteractEvent e) {
        if (e.getAction().isRightClick() && e.getClickedBlock() != null && e.getClickedBlock().getType() == Material.SPAWNER) {
            e.setCancelled(true);
            Location loc = e.getClickedBlock().getLocation();
            active.add(loc.clone());
            openGui(e.getPlayer(), loc);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (e.getBlockPlaced().getType() != Material.SPAWNER) return;
        Location loc = e.getBlockPlaced().getLocation();
        ItemStack item = e.getItemInHand();
        SpawnerState state = readItemState(item);
        CreatureSpawner cs = (CreatureSpawner) loc.getBlock().getState();
        if (state != null) {
            cs.setSpawnedType(state.type()); cs.update(true, false);
            store.put(loc, state);
        } else {
            SpawnerState fresh = new SpawnerState();
            if (cs.getSpawnedType() != null) fresh.type(cs.getSpawnedType());
            store.put(loc, fresh);
        }
        active.add(loc.clone());
        nextProduction.put(key(loc), System.currentTimeMillis() + intervalSeconds(store.get(loc).level())*1000L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(BlockDropItemEvent e) {
        if (e.getBlockState().getType() != Material.SPAWNER || store == null) return;
        Location loc = e.getBlock().getLocation();
        SpawnerState state = store.get(loc);
        for (org.bukkit.entity.Item entity : e.getItems()) {
            ItemStack stack = entity.getItemStack();
            if (stack.getType() == Material.SPAWNER) {
                entity.setItemStack(createSpawnerItem(state));
            }
        }
        store.remove(loc); active.remove(loc); nextProduction.remove(key(loc));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (e.getBlock().getType() == Material.SPAWNER) {
            Location loc = e.getBlock().getLocation();
            active.add(loc.clone());
            // State remains available until BlockDropItemEvent converts the actual spawner drop.
        }
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) {
        Location loc = openSpawners.remove(e.getPlayer().getUniqueId());
        if (loc != null) saveGui(e.getPlayer().getOpenInventory().getTopInventory(), loc);
    }

    private void openGui(Player player, Location loc) {
        SpawnerState state = store.get(loc);
        Inventory inv = Bukkit.createInventory(new SpawnerHolder(loc), 54, Component.text(TITLE));
        int slots = getConfig().getInt("settings.storage-slots", 45);
        int i=0; for (ItemStack item : state.drops()) { if (i>=slots) break; inv.setItem(i++, item.clone()); }
        fillButton(inv, 45, Material.EXPERIENCE_BOTTLE, "xᴘ sᴛᴏʀᴀɢᴇ", "<green>"+format(state.storedXp())+" xᴘ</green>", "cʟɪᴄᴋ ᴛᴏ ᴄᴏʟʟᴇᴄᴛ");
        fillButton(inv, 49, Material.SPAWNER, "ʟᴇᴠᴇʟ "+state.level(), "<gray>ᴛʏᴘᴇ: <white>"+pretty(state.type().name())+"</white></gray>", "<gray>ᴜᴘɢʀᴀᴅᴇ ᴄᴏsᴛs: <white>"+upgradeCostText(state.level())+"</white></gray>");
        fillButton(inv, 53, Material.EMERALD, "ᴜᴘɢʀᴀᴅᴇ", "<gray>ᴍᴀx ʟᴇᴠᴇʟ: <white>3</white></gray>", "<yellow>ᴄʟɪᴄᴋ ᴛᴏ ᴜᴘɢʀᴀᴅ</yellow>");
        openSpawners.put(player.getUniqueId(), loc.clone());
        player.openInventory(inv);
    }

    private void fillButton(Inventory inv, int slot, Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material); ItemMeta meta=item.getItemMeta();
        meta.displayName(Component.text(name));
        List<Component> lines=new ArrayList<>(); for(String line:lore) lines.add(Component.text(strip(line)));
        meta.lore(lines); item.setItemMeta(meta); inv.setItem(slot,item);
    }
    private String strip(String s) { return ChatColor.translateAlternateColorCodes('&', s.replaceAll("<[^>]+>", "")); }
    private String format(double n) { return String.format(Locale.US, "%,.0f", n); }
    private String pretty(String s) { return s.toLowerCase(Locale.ROOT).replace('_',' '); }
    private String upgradeCostText(int level) { return level>=3?"ᴍᴀx":String.format(Locale.US,"$%,.0f",upgradeCost(level)); }
    private double upgradeCost(int level) { return getConfig().getDouble("settings.upgrades.level-"+(level+1)+"-cost", level==1?1_000_000:10_000_000); }

    @EventHandler public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof SpawnerHolder holder)) return;
        int raw=e.getRawSlot();
        if (raw == 45) { e.setCancelled(true); collectXp((Player)e.getWhoClicked(), holder.location()); return; }
        if (raw == 49 || raw == 53) { e.setCancelled(true); if (raw==53) upgrade((Player)e.getWhoClicked(), holder.location()); return; }
        if (raw >= 45 && raw < e.getView().getTopInventory().getSize()) e.setCancelled(true);
    }

    @EventHandler public void onDrag(InventoryDragEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof SpawnerHolder)) return;
        for (int slot : e.getRawSlots()) if (slot >= 45) { e.setCancelled(true); return; }
    }

    @EventHandler public void onClose(InventoryCloseEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof SpawnerHolder holder)) return;
        Player p=(Player)e.getPlayer(); saveGui(e.getView().getTopInventory(), holder.location()); openSpawners.remove(p.getUniqueId());
    }

    private void saveGui(Inventory inv, Location loc) {
        if (store == null || loc.getBlock().getType()!=Material.SPAWNER) return;
        SpawnerState state=store.get(loc); state.drops().clear();
        for(int i=0;i<45;i++){ ItemStack item=inv.getItem(i); if(item!=null&&!item.getType().isAir()) state.drops().add(item.clone()); }
        store.put(loc,state);
    }

    private void collectXp(Player p, Location loc) {
        SpawnerState state=store.get(loc); if(state.storedXp()<=0){p.sendActionBar(Component.text("nᴏ xᴘ ɪɴ sᴛᴏʀᴀɢᴇ", NamedTextColor.GRAY));return;}
        double amount=state.storedXp(); int whole=(int)Math.min(Integer.MAX_VALUE, Math.floor(amount));
        if(whole>0) p.giveExp(whole); state.storedXp(amount-whole); store.put(loc,state); openGui(p,loc);
    }

    private void upgrade(Player p, Location loc) {
        SpawnerState state=store.get(loc); if(state.level()>=3){p.sendMessage(Component.text("sᴍᴀʀᴛ sᴘᴀᴡɴᴇʀ ɪs ᴀʟʀᴇᴀᴅʏ ʟᴇᴠᴇʟ 3",NamedTextColor.YELLOW));return;}
        double cost=upgradeCost(state.level()); if(!economy.has(p,cost)){p.sendMessage(Component.text("ɴᴏᴛ ᴇɴᴏᴜɢʜ ᴍᴏɴᴇʏ: $"+format(cost),NamedTextColor.RED));return;}
        economy.withdrawPlayer(p,cost); state.level(state.level()+1); store.put(loc,state); nextProduction.put(key(loc),System.currentTimeMillis());
        p.sendMessage(Component.text("sᴘᴀᴡɴᴇʀ ᴜᴘɢʀᴀᴅᴇᴅ ᴛᴏ ʟᴇᴠᴇʟ "+state.level(),NamedTextColor.GREEN)); openGui(p,loc);
    }

    private ItemStack createSpawnerItem(SpawnerState state) {
        ItemStack item=new ItemStack(Material.SPAWNER); ItemMeta meta=item.getItemMeta();
        meta.displayName(Component.text(TITLE+" "+state.level()));
        List<Component> lore=new ArrayList<>(); lore.add(Component.text("ᴛʏᴘᴇ: "+pretty(state.type().name()))); lore.add(Component.text("ʟᴇᴠᴇʟ: "+state.level()+"/3")); lore.add(Component.text("xᴘ: "+format(state.storedXp()))); lore.add(Component.text("ᴅʀᴏᴘs: "+state.drops().size()+" sᴛᴀᴄᴋs")); meta.lore(lore);
        meta.getPersistentDataContainer().set(itemStateKey,PersistentDataType.STRING,state.serialize()); meta.getPersistentDataContainer().set(itemMarkerKey,PersistentDataType.BYTE,(byte)1); item.setItemMeta(meta); return item;
    }

    private SpawnerState readItemState(ItemStack item) {
        if(item==null||item.getType()!=Material.SPAWNER||!item.hasItemMeta()) return null;
        String raw=item.getItemMeta().getPersistentDataContainer().get(itemStateKey,PersistentDataType.STRING);
        return raw==null?null:SpawnerState.deserialize(raw);
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if(!(sender.hasPermission("smartspawner.admin"))){sender.sendMessage(Component.text("nᴏ ᴘᴇʀᴍɪssɪᴏɴ",NamedTextColor.RED));return true;}
        if(args.length>=3 && args[0].equalsIgnoreCase("give")){
            Player target=Bukkit.getPlayerExact(args[1]); if(target==null){sender.sendMessage(Component.text("ᴘʟᴀʏᴇʀ ɴᴏᴛ ғᴏᴜɴᴅ",NamedTextColor.RED));return true;}
            EntityType type; try{type=EntityType.valueOf(args[2].toUpperCase(Locale.ROOT));}catch(Exception ex){sender.sendMessage(Component.text("ɪɴᴠᴀʟɪᴅ ᴇɴᴛɪᴛʏ ᴛʏᴘᴇ",NamedTextColor.RED));return true;}
            int amount=args.length>=4?Math.max(1,Integer.parseInt(args[3])):1; SpawnerState s=new SpawnerState();s.type(type);ItemStack item=createSpawnerItem(s);item.setAmount(Math.min(item.getMaxStackSize(),amount));target.getInventory().addItem(item);sender.sendMessage(Component.text("ɢɪᴠᴇɴ sᴍᴀʀᴛ sᴘᴀᴡɴᴇʀ",NamedTextColor.GREEN));return true;
        }
        sender.sendMessage(Component.text("/smartspawner give <player> <entity> [amount]",NamedTextColor.GRAY));return true;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args){
        if(args.length==1)return List.of("give"); if(args.length==3){String q=args[2].toUpperCase(Locale.ROOT);return Arrays.stream(EntityType.values()).filter(EntityType::isAlive).map(Enum::name).filter(n->n.startsWith(q)).limit(50).toList();} return List.of();
    }

    private record SpawnerHolder(Location location) implements InventoryHolder { @Override public Inventory getInventory(){return null;} }
}
