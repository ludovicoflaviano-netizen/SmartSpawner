# SmartSpawner

SmartSpawner is a Paper 1.21.11 plugin that turns mob spawners into persistent virtual-storage machines.

## Features

- Right-click a spawner to open a 54-slot chest-style storage GUI.
- Generated mob loot is stored virtually instead of spawning item drops into the world.
- Uses Minecraft loot tables when available, with a safe fallback for common mobs.
- Stores XP virtually and lets the owner collect it from the GUI.
- Three persistent levels: 1, 2 and 3.
- Expensive Vault economy upgrades: level 2 costs $1,000,000 and level 3 costs $10,000,000 by default.
- Level upgrades improve production interval, drop multiplier and XP generation.
- Spawner level, type, XP and stored drops survive pickup and placement through item persistent data.
- No custom Silk Touch system is included. Existing spawner pickup behavior from EconomyShopGUI or another server system is left alone; when a spawner item passes through this plugin, its SmartSpawner state is restored.
- Persistent storage is saved in `plugins/SmartSpawner/spawners.yml`.

## Dependencies

- Paper 1.21.11
- Vault
- A Vault-compatible economy provider
- EconomyShopGUI is optional and can continue handling the server's existing spawner pickup/silk behavior.

## Command

`/smartspawner give <player> <entity> [amount]`

Permission: `smartspawner.admin`

Example: `/smartspawner give Steve ZOMBIE 1`

## Build

Use Java 21 and Maven:

```text
mvn clean package
```

The resulting jar is in `target/SmartSpawner-1.0.0.jar`.
