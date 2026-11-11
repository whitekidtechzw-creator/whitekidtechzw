# WHITEKID TECH — v2 (Netlify)

Your original portfolio, upgraded. All five projects, links, branding, photo hero, About, Skills, Contact and the original look are preserved.

## Deploy
Drag-and-drop does NOT run functions. Use Git or `netlify deploy --prod` (CLI) with this folder as the root.
`netlify.toml` sets publish dir `public` and functions dir `netlify/functions`.

## Environment variables (Site settings → Environment variables)
| Name | Required | Purpose |
|---|---|---|
| `ANTHROPIC_API_KEY` | for WHITEKID AI | Server-side only, never sent to the browser |
| `AI_MODEL` | no | Defaults to `claude-sonnet-5-5` |
| `GITHUB_TOKEN` | no | Raises GitHub API rate limits (no scopes needed) |

## What works now
- WHITEKID AI chat (history saved in the visitor's browser, typing animation, rate limited 12/min/IP)
- Live GitHub panel (profile, latest repos, public activity) via GitHub API
- Real status checks of the 3 website projects; Telegram bots are shown as N/A (they can't be probed)
- Project search + sort + filter
- Terminal 2.0: help about projects skills github contact socials achievements roadmap status ai clear date version
- Contact form + newsletter via Netlify Forms (enable form notifications / view submissions in the Netlify dashboard)
- SEO: title/description, Open Graph, Twitter card, canonical, JSON-LD (Person, WebSite, project list), sitemap.xml, robots.txt

## Not built yet (needs your data or a database)
Owner Control Center, blog, playground, store, analytics, ratings, roadmap/achievements content (empty arrays `ROADMAP`/`ACHIEVEMENTS` in index.html — terminal says "none published yet"), per-project screenshots/repos/release info.
No fake numbers are shown anywhere.
