# AlloyShop

A chest shop system with sign-based creation, GUI browsing, and economy integration.

## What It Does

AlloyShop lets players create shops by placing a sign next to a chest. Customers browse items through a clean GUI, adjust quantities, and buy or sell with a click. Shop owners set per-item buy and sell prices, and earnings accumulate until collected. Three shop types cover every use case: player-owned shops with physical stock, admin shops with infinite supply, and server reserve shops backed by a real chest.

### Key Features

- **Instant Shop Creation** — Place a `[Shop]` sign next to a chest and you're open for business
- **GUI Shopping** — Browse, buy, and sell through point-and-click menus (no commands needed)
- **Three Shop Types** — Player shops, admin shops (infinite stock), and server reserve shops
- **Per-Item Pricing** — Independent buy and sell prices for every material
- **Bulk Transactions** — Shift-click for x16 increments, or type exact amounts via commands
- **Earnings System** — Revenue accumulates and is collected on demand
- **Block Protection** — Shop signs and chests are protected from breaking and explosions
- **Economy Agnostic** — Works with any Alloy economy provider

## Getting Started

### For Players

1. **Build a shop** — Place a chest, fill it with items, then place a sign on it with `[Shop]` on the first line
2. **Set prices** — Sneak + right-click your shop sign to open the owner menu and set buy/sell prices per item
3. **Collect earnings** — Run `/shop collect` or use the owner menu to withdraw your revenue
4. **Buy from others** — Right-click any shop sign to browse and purchase items

### For Server Owners

1. Drop `AlloyShop-1.0.0.jar` into your `mods/` folder
2. Start the server — a `config.json` will be generated in `alloy-shop/`
3. Create admin shops with `[AdminShop]` signs (requires OP)
4. Create server reserve shops with `[Shop]` + `reserve` on the second line

## Commands

| Command | Usage |
|---------|-------|
| `/shop help` | Show all commands |
| `/shop info` | View details of nearest shop |
| `/shop list [player]` | List shops by owner |
| `/shop price <item> <buy> [sell]` | Set item prices |
| `/shop stock <item> <amount>` | Add stock from inventory |
| `/shop withdraw <item> <amount>` | Pull stock to inventory |
| `/shop buy <item> [amount]` | Buy from last viewed shop |
| `/shop sell <item> [amount]` | Sell to last viewed shop |
| `/shop earnings` | View pending earnings |
| `/shop collect` | Collect all earnings |
| `/shop remove` | Remove your shop |

## Shop Types

**Player Shops** — Created by any player. Stock comes from the physical chest. Earnings accumulate until the owner collects them.

**Admin Shops** — Created by OPs with `[AdminShop]` signs. Infinite stock, infinite funds. No chest needed.

**Reserve Shops** — Server-owned shops backed by a real chest. Created with `[Shop]` + `reserve` on line 2. Any OP can manage them.

## GUI Menus

- **Customer Browse** — See all items for sale, click to open purchase screen
- **Purchase Screen** — Adjust quantity (+1, +16, -1, -16), see total cost, confirm buy
- **Sell Browse** — See items the shop will buy from you
- **Sell Screen** — Adjust quantity, see total payment, confirm sell
- **Owner Menu** — View all stocked items, edit prices, collect earnings
- **Price Editor** — Fine-tune buy/sell prices with $0.01 / $0.05 / $0.10 / $1.00 increments

## Configuration

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

*Part of the AlloyMC modding ecosystem*
