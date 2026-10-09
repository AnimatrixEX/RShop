package com.rshop.data.artwork

/**
 * Maps the console names sites use ("SNES", "Mega Drive", "PS2"…) to the folder of the same console
 * on thumbnails.libretro.com. First match wins, so the specific names come before the general ones
 * ("Game Boy Advance" before "Game Boy").
 */
object LibretroSystems {
    private class Rule(pattern: String, val directory: String) {
        val regex = Regex("(?i)$pattern")
    }

    private val rules = listOf(
        Rule("neo ?geo ?pocket ?colou?r|\\bngpc\\b", "SNK - Neo Geo Pocket Color"),
        Rule("neo ?geo ?pocket|\\bngp\\b", "SNK - Neo Geo Pocket"),
        Rule("neo ?geo ?cd", "SNK - Neo Geo CD"),
        Rule("neo ?geo|\\baes\\b|\\bmvs\\b", "SNK - Neo Geo"),
        Rule("wonder ?swan ?colou?r", "Bandai - WonderSwan Color"),
        Rule("wonder ?swan", "Bandai - WonderSwan"),
        Rule("game ?boy ?advance|\\bgba\\b", "Nintendo - Game Boy Advance"),
        Rule("game ?boy ?colou?r|\\bgbc\\b", "Nintendo - Game Boy Color"),
        Rule("game ?boy|\\bgb\\b", "Nintendo - Game Boy"),
        Rule("virtual ?boy", "Nintendo - Virtual Boy"),
        Rule("pok[eé]mon ?mini", "Nintendo - Pokemon Mini"),
        Rule("3ds", "Nintendo - Nintendo 3DS"),
        Rule("nintendo ?dsi|\\bdsi\\b", "Nintendo - Nintendo DSi"),
        Rule("nintendo ?ds|\\bnds\\b", "Nintendo - Nintendo DS"),
        Rule("64 ?dd", "Nintendo - Nintendo 64DD"),
        Rule("nintendo ?64|\\bn64\\b", "Nintendo - Nintendo 64"),
        Rule("game ?cube|\\bgcn?\\b|\\bngc\\b", "Nintendo - GameCube"),
        Rule("wii ?u", "Nintendo - Wii U"),
        Rule("\\bwii\\b", "Nintendo - Wii"),
        Rule("satellaview", "Nintendo - Satellaview"),
        Rule("famicom ?disk|\\bfds\\b", "Nintendo - Family Computer Disk System"),
        Rule("super ?(nintendo|famicom|nes)|\\bsnes\\b|\\bsfc\\b", "Nintendo - Super Nintendo Entertainment System"),
        Rule("nintendo entertainment|\\bnes\\b|famicom", "Nintendo - Nintendo Entertainment System"),
        Rule("mega ?-?cd|sega ?cd", "Sega - Mega-CD - Sega CD"),
        Rule("32 ?x", "Sega - 32X"),
        Rule("mega ?drive|genesis|\\bmd\\b", "Sega - Mega Drive - Genesis"),
        Rule("master ?system|\\bsms\\b|mark iii", "Sega - Master System - Mark III"),
        Rule("game ?gear|\\bgg\\b", "Sega - Game Gear"),
        Rule("saturn", "Sega - Saturn"),
        Rule("dreamcast|\\bdc\\b", "Sega - Dreamcast"),
        Rule("sg-?1000", "Sega - SG-1000"),
        Rule("naomi ?2", "Sega - Naomi 2"),
        Rule("naomi", "Sega - Naomi"),
        Rule("playstation ?portable|\\bpsp\\b", "Sony - PlayStation Portable"),
        Rule("playstation ?vita|ps ?vita", "Sony - PlayStation Vita"),
        Rule("playstation ?4|\\bps4\\b", "Sony - PlayStation 4"),
        Rule("playstation ?3|\\bps3\\b", "Sony - PlayStation 3"),
        Rule("playstation ?2|\\bps2\\b", "Sony - PlayStation 2"),
        Rule("playstation|\\bpsx\\b|\\bps1\\b|\\bpsone\\b", "Sony - PlayStation"),
        Rule("(pc ?engine|turbo ?grafx).*cd|turbo ?duo", "NEC - PC Engine CD - TurboGrafx-CD"),
        Rule("super ?grafx", "NEC - PC Engine SuperGrafx"),
        Rule("pc ?engine|turbo ?grafx|\\bpce\\b|\\btg-?16\\b", "NEC - PC Engine - TurboGrafx 16"),
        Rule("pc-?fx", "NEC - PC-FX"),
        Rule("atari ?2600|\\b2600\\b", "Atari - 2600"),
        Rule("atari ?5200|\\b5200\\b", "Atari - 5200"),
        Rule("atari ?7800|\\b7800\\b", "Atari - 7800"),
        Rule("jaguar", "Atari - Jaguar"),
        Rule("lynx", "Atari - Lynx"),
        Rule("atari ?st", "Atari - ST"),
        Rule("3do", "The 3DO Company - 3DO"),
        Rule("xbox ?360", "Microsoft - Xbox 360"),
        Rule("xbox", "Microsoft - Xbox"),
        Rule("msx ?2", "Microsoft - MSX2"),
        Rule("\\bmsx\\b", "Microsoft - MSX"),
        Rule("colecovision|coleco", "Coleco - ColecoVision"),
        Rule("intellivision", "Mattel - Intellivision"),
        Rule("vectrex", "GCE - Vectrex"),
        Rule("commodore ?64|\\bc64\\b", "Commodore - 64"),
        Rule("cd32", "Commodore - CD32"),
        Rule("amiga", "Commodore - Amiga"),
        Rule("philips ?cd-?i|\\bcd-?i\\b", "Philips - CD-i"),
        Rule("zx ?spectrum", "Sinclair - ZX Spectrum"),
        Rule("amstrad ?cpc|\\bcpc\\b", "Amstrad - CPC"),
    )

    /** The Libretro folder of [platform], or null for a console it has no art for. */
    fun directoryFor(platform: String?): String? {
        val name = platform?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return rules.firstOrNull { it.regex.containsMatchIn(name) }?.directory
    }
}
