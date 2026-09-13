# SmartSpawner

Paper 1.21.11 virtual-storage spawners with a clean native chest UI and persistent upgrade branches.

## Features
- Detects the actual mob type from the placed spawner before opening the GUI.
- Clean small-cap UI with the detected mob shown in the title and info card.
- Virtual mob drops and XP; no physical mob spawning is required for production.
- Loot is generated from the Minecraft entity loot table when available, with safe fallbacks.
- Three independent persistent branches, each capped at level 3: `ᴘʀᴏᴅᴜᴄᴛɪᴏɴ`, `sᴛᴏʀᴀɢᴇ`, and `ᴇғғɪᴄɪᴇɴᴄʏ`.
- Expensive Vault upgrades: production $1,000,000/$10,000,000; storage $2,500,000/$25,000,000; efficiency $5,000,000/$50,000,000.
- Storage is stack-aware and paginated, so storage upgrades do not silently delete overflow.
- Spawner type, branch levels, stored drops and stored XP persist through pickup and placement using item PDC data.
- No custom Silk Touch system; existing EconomyShopGUI/server pickup behavior is left intact.

## Command
`/smartspawner give <player> <entity> [amount]`

Permission: `smartspawner.admin`

## Build
Use Java 21 and Maven:

```text
mvn clean package
```
