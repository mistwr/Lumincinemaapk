#!/usr/bin/env python3
"""Gera youtube.json: filmes, séries, desenhos e documentários COMPLETOS publicados de graça
pelos próprios donos dos direitos nos seus canais oficiais do YouTube.
Tudo toca dentro da LUMIN pelo player oficial do YouTube (embed), sem login.

Uso:  pip install yt-dlp && python3 tools/youtube_catalog.py [por_canal]
Regra: só entram canais OFICIAIS (estúdio, distribuidor ou dono da marca). Não juntar canais de reuploads.
"""
import json, re, subprocess, sys
from concurrent.futures import ThreadPoolExecutor

PER = int(sys.argv[1]) if len(sys.argv) > 1 else 400

# (url, nome, categoria, língua, duração mínima em segundos[, máximo de vídeos a ler])
CHANNELS = [
    # Filmes completos
    ("user/mosfilm", "Mosfilm", "filmes", "ru/en", 3600, 4000),
    ("@GorkyFilmStudio", "Estúdio Gorky", "filmes", "ru/en", 3600, 4000),
    ("@FilmRiseMovies", "FilmRise Movies", "filmes", "en", 3600, 4000),
    ("@Popcornflix", "Popcornflix", "filmes", "en", 3600, 4000),
    ("@MovieCentral", "Movie Central", "filmes", "en", 3600, 4000),
    ("@MidnightPulp", "Midnight Pulp", "filmes", "en", 3600),
    ("@UltraMovieParlour", "Ultra Movie Parlour (Bollywood)", "filmes", "hi/en", 3600, 3000),
    ("@ShemarooMovies", "Shemaroo Movies (Índia)", "filmes", "hi/en", 3600, 3000),
    ("@GoldminesTelefilms", "Goldmines (Índia)", "filmes", "hi/en", 3600, 3000),
    ("@MaverickMovies", "Maverick Movies", "filmes", "en", 3600, 3000),
    ("@CrotonMegaHit", "Croton Mega Hit (dramas chineses)", "series", "zh/en", 1800, 3000),
    ("@SonyLIV", "Sony LIV (séries indianas)", "series", "hi", 1200, 1500),
    ("@RTVEplay", "RTVE Play (Espanha)", "series", "es", 2400, 1500),
    ("@FilmRiseKids", "FilmRise Kids", "familia", "en", 1200),
    # Séries e desenhos animados (episódios e compilações oficiais)
    ("@PocoyoBrasil", "Pocoyo (português)", "desenhos", "pt", 600),
    ("@TurmadaMonica", "Turma da Mônica", "desenhos", "pt", 600),
    ("@GalinhaPintadinha", "Galinha Pintadinha", "desenhos", "pt", 300),
    ("@MundoBita", "Mundo Bita", "desenhos", "pt", 300),
    ("@MoonbugKidsPortugues", "Moonbug Kids (português)", "desenhos", "pt", 600),
    ("@OddbodsBrasil", "Oddbods (português)", "desenhos", "pt", 600),
    ("@BlippiPortugues", "Blippi (português)", "desenhos", "pt", 600),
    ("@LuccasToon", "Luccas Toon", "desenhos", "pt", 600),
    ("@MashaBearEN", "Masha e o Urso", "desenhos", "en", 600),
    ("@Soyuzmultfilm", "Soyuzmultfilm", "desenhos", "ru/en", 600),
    ("@Teletubbies", "Teletubbies", "desenhos", "en", 600),
    ("@ThomasAndFriends", "Thomas & Friends", "desenhos", "en", 600),
    ("@LarvaTUBA", "Larva", "desenhos", "en", 480),
    ("@PeppaPigOfficial", "Peppa Pig", "desenhos", "en", 600),
    ("@Oddbods", "Oddbods", "desenhos", "en", 600),
    ("@MrBean", "Mr Bean", "series", "en", 900),
    ("@PowerRangersOfficial", "Power Rangers", "series", "en", 1200),
    ("@RetroCrush", "RetroCrush (anime)", "series", "en", 1200),
    ("@KitchenNightmares", "Kitchen Nightmares", "series", "en", 1500),
    ("@HellsKitchen", "Hell's Kitchen", "series", "en", 1500),
    ("@FilmRiseTrueCrime", "FilmRise True Crime", "series", "en", 1500),
    # Documentários
    ("@dwbrasil", "DW Brasil", "docs", "pt", 900),
    ("@FreeDocumentaryHistory", "Free Documentary History", "docs", "en", 1500),
    ("@AbsoluteHistory", "Absolute History", "docs", "en", 1500),
    ("@RealCrime", "Real Crime", "docs", "en", 1500),
    ("@DWDocumentary", "DW Documentary", "docs", "en", 1200),
    ("@ARTEtvDocumentary", "ARTE.tv Documentary", "docs", "en", 1200),
    ("@FreeDocumentary", "Free Documentary", "docs", "en", 1500),
    ("@FreeDocumentaryNature", "Free Documentary Nature", "docs", "en", 1500),
    ("@RealStories", "Real Stories", "docs", "en", 1500),
    ("@TimelineChannel", "Timeline (História)", "docs", "en", 1500),
    ("@DocumentaryPlus", "Documentary+", "docs", "en", 1500),
]
SKIP = re.compile(r"trailer|teaser|\bclip\b|#shorts|\bshorts?\b|preview|sneak peek|behind the scenes|live ?stream|\bpromo\b|\bdiva trl", re.I)

def fetch(ch):
    url, name, cat, lang, mind = ch[:5]
    per = ch[5] if len(ch) > 5 else PER
    try:
        out = subprocess.run(["yt-dlp", "--flat-playlist", "-J", "--playlist-end", str(per),
                              f"https://www.youtube.com/{url}/videos"], capture_output=True, text=True, timeout=900).stdout
        d = json.loads(out)
    except Exception as e:
        print("FALHOU", name, e, file=sys.stderr); return ch, []
    items = []
    for e in d.get("entries") or []:
        t, dur = (e.get("title") or "").strip(), e.get("duration") or 0
        if not e.get("id") or dur < mind or SKIP.search(t) or t in ("[Private video]", "[Deleted video]"):
            continue
        items.append([e["id"], t[:140], int(dur), int(e.get("view_count") or 0)])
    print(f"{name}: {len(items)}", file=sys.stderr)
    return ch, items

def main():
    with ThreadPoolExecutor(6) as ex:
        res = list(ex.map(fetch, CHANNELS))
    chans, items = [], []
    for i, (ch, its) in enumerate(res):
        chans.append({"n": ch[1], "cat": ch[2], "lang": ch[3], "url": "https://www.youtube.com/" + ch[0]})
        items += [[v, t, d, i, views] for v, t, d, views in its]
    json.dump({"channels": chans, "items": items}, open("youtube.json", "w", encoding="utf-8"),
              ensure_ascii=False, separators=(",", ":"))
    print("TOTAL", len(items), file=sys.stderr)

if __name__ == "__main__":
    main()
