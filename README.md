# Outbreak

**Villagers get sick. You figure out why — and help them get better.**

Outbreak is a survival mod for Fabric. Every now and then a villager near you falls ill. Sick villagers sneeze, cough, shiver or clutch their stomachs, and some illnesses spread to their neighbours if nobody helps. Spot the symptoms, take a swab, study it under a microscope, make a diagnosis and give the right treatment.

No commands or config editing needed — install it and play.

## Requirements
- Minecraft Java **26.1.2**
- Fabric Loader **0.19.5** or newer
- Fabric API **0.155.3** or newer
- Java 25

Install it on **both the client and the server**. Players without the mod cannot join a server that has it.

## Getting started
All tools are crafted from vanilla materials. They appear in your recipe book as you pick up the ingredients, and crafting the Medical Journal unlocks the rest of the kit.

| Item | Recipe |
|---|---|
| Sterile Swab (×2) | Stick + String |
| Medical Journal | Book + Sterile Swab |
| Empty Petri Dish (×2) | 3 Glass Panes (in a V shape) |
| Examination Tool | Glass Pane, Copper Ingot, Stick (in a column) |
| Microscope | Glass Pane and Copper Ingot (stacked) on top of 3 Iron Ingots |
| Supportive Tonic (×2) | Honey Bottle + Sweet Berries + Dandelion |
| Antihistamine (×2) | Sugar + Bone Meal + Paper |
| Oral Rehydration Salts (×2) | Glass Bottle + 2 Sugar + Dried Kelp |

## How to play
1. **Notice** — an ill villager shows symptoms: head shakes, sneezes, coughs, shivers, small particles. Many illnesses look alike.
2. **Examine** — use the Examination Tool on a villager to see which symptoms they have shown lately and how often.
3. **Swab** — use a Sterile Swab on the villager while you carry an Empty Petri Dish. You get a Prepared Petri Dish. Samples go stale after three in-game days.
4. **Diagnose** — place the dish on a Microscope and open it. Focus the image, examine the sample at each magnification, then choose a diagnosis. The Medical Journal explains what to look for.
5. **Treat** — use the matching treatment on the sick villager. A wrong treatment is never used up.

Your diagnoses are saved in your Medical Journal, which also has background pages on each illness. A small corner display lists cases you confirmed nearby; a line disappears when you treat that villager, or after 7 in-game days.

Untreated villagers usually recover on their own after a few days, but they can pass the illness on in the meantime. Treated villagers recover much faster and are far less contagious.

## Illnesses
| Illness | What you might notice | Contagious? |
|---|---|---|
| Common Cold | sneezing, coughing | yes, close contact |
| Seasonal Allergies | sneezing | no |
| Influenza | coughing, shivering, sneezing | yes, further than a cold — even before symptoms show |
| Food Poisoning | stomach clutching, shivering | only to villagers carrying food |

The weather, time of day, biome and a villager's age all change how often each illness appears. Players and other mobs never get sick.

## When illness appears
- Villagers fall ill on their own only near players, and only rarely: roughly one new case per village every few in-game days.
- A new world has a one-day grace period before the first case. In an existing world, illness can start a few minutes after the mod is installed.
- Limits stop many new cases happening at once in one place.
- Nothing is announced. You only find out from the symptoms.

## Configuration
Server settings are in `config/outbreak.json`, in your game folder (or the server folder). The file is created on first start and never overwritten, so new settings in later versions keep their defaults until you add them. Run `/outbreak config reload` after editing; `/outbreak config show` prints the current values and the file's full path.

Useful keys:
- `onset.enabled` — villagers fall ill on their own (default `true`)
- `onset.chance_multiplier` — how often they do (default `1.0`; `0.5` = half as often)
- `spread.enabled` — illnesses spread between villagers (default `true`)
- `spread.chance_multiplier` — how easily they spread (default `1.0`)
- `symptoms.frequency_multiplier` — how often symptoms show (default `1.0`)
- `symptoms.pause_ticks` — how long a villager pauses while showing a symptom (default `40`; `0` = never)

Personal display settings are in `config/outbreak-client.json` (`hud.enabled`, `hud.max_lines`, `hud.nearby_radius_blocks`, `hud.age_out_days`). Restart the game after editing it.

### Datapacks
Illnesses are data-driven. A datapack can change an illness by providing `data/outbreak/outbreak/illness/<id>.json` (ids: `common_cold`, `seasonal_allergies`, `influenza`, `food_poisoning`). Unknown or invalid values are reported in the log.

## Admin commands
For testing and server admins only (permission level 2). Normal play never needs them.
- `/outbreak infect|cure|treat|stage|inspect|list …` — manage individual villagers
- `/outbreak illnesses` — list loaded illnesses and their settings
- `/outbreak spread status|pass|reset|enabled|chance|range` — spread controls
- `/outbreak onset status|check|trigger [illness]|reset` — natural illness controls
- `/outbreak config reload|show`

## Compatibility
- **Existing worlds:** safe to add. All existing villagers start healthy.
- **Removing the mod:** the world still loads. Outbreak items, Microscope blocks and villager illness data are removed. Make a backup first, as with any mod.
- No mixins; Fabric API is the only dependency.

## Known issues
- The tools and treatments only work from the main hand.
- The swab sound is a placeholder.
- A zombie villager that is cured becomes a new villager. If you diagnose it again, the corner display can briefly show the case twice.
- Samples of an illness that a datapack has since removed cannot be diagnosed correctly.

## Disclaimer
Outbreak is a game. Its illnesses, symptoms and treatments are simplified and are **not medical advice**. If you feel unwell, talk to a doctor or pharmacist.

## License
Copyright © 2026 Velora. **All rights reserved.** See `LICENSE`.
You may use the unmodified mod in your worlds, on servers and in modpacks. Redistributing, modifying or reusing its code or assets requires permission.
Minecraft is a trademark of Mojang Synergies AB. Outbreak is not affiliated with Mojang or Microsoft.