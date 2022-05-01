# AlloyShop — Forged with Alloy

Chest shop system for Minecraft, built from scratch for the Alloy modding ecosystem.

Sign-based shop creation with GUI browsing, economy integration, and full block protection.

## Features

- **Sign + Chest Shops** — Place a `[Shop]` sign next to a chest to create a shop instantly
- **GUI Shopping** — Customers browse items, adjust quantities, and buy/sell through intuitive menus
- **Three Shop Types** — Player shops (physical stock), admin shops (infinite), and reserve/server shops
- **Economy Integration** — Works with any Alloy economy provider (Dilithium, file-based, etc.)
- **Per-Item Pricing** — Set independent buy and sell prices for every item in your shop
- **Block Protection** — Shop signs and chests protected from breaking and explosions
- **Flat File Storage** — JSON-based persistence, one file for all shops

## Quick Start

1. Drop `AlloyShop-1.0.0.jar` into your server's `mods/` folder
2. Place a chest and put items in it
3. Place a sign on or next to the chest with `[Shop]` on the first line
4. Sneak + right-click the sign to open the owner menu and set prices
5. Other players right-click the sign to browse and buy

## Shop Types

| Sign Text | Type | Stock | Who Can Create |
|-----------|------|-------|----------------|
| `[Shop]` | Player Shop | From chest | Any player |
| `[AdminShop]` | Admin Shop | Infinite | OPs / admins |
| `[Shop]` + `reserve` on line 2 | Server Shop | From chest | OPs / admins |

## Commands

All commands are subcommands of `/shop`:

| Command | Description |
|---------|-------------|
| `/shop help` | Show all commands |
| `/shop info [id]` | Display shop details |
| `/shop list [player]` | List shops by owner |
| `/shop price <item> <buy> [sell]` | Set buy/sell prices for an item |
| `/shop stock <item> <amount>` | Add stock from inventory to chest |
| `/shop withdraw <item> <amount>` | Remove stock from chest to inventory |
| `/shop buy <item> [amount]` | Buy items from last viewed shop |
| `/shop sell <item> [amount]` | Sell items to last viewed shop |
| `/shop earnings [id]` | View pending earnings |
| `/shop collect [id]` | Collect earnings to your balance |
| `/shop remove [id]` | Remove a shop you own |
| `/shop admin create` | Instructions for creating admin shops |

## Permissions

| Permission | Default | Description |
|------------|---------|-------------|
| `alloyshop.use` | Everyone | Use shop commands and browse GUIs |
| `alloyshop.create` | Everyone | Create player shops via signs |
| `alloyshop.admin` | OP | Create admin/reserve shops, manage any shop |

## Configuration

Generated at `<server-data>/alloy-shop/config.json`:

```json
{
  "max_shops_per_player": 10,
  "tax_rate": 0.0,
  "allow_admin_shops": true
}
```

## Requirements

- Alloy mod loader
- An economy provider (e.g., Dilithium Economy)
- Java 21+

---

*Part of the [AlloyMC](https://alloymc.net) modding ecosystem*
