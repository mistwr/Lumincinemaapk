# LUMIN Filmes (Android / Android TV)

App de filmes grátis e legais: só filmes em **domínio público na UE** ou com **licença Creative Commons**.
A interface vai dentro da APK (`app/src/main/assets/index.html`), por isso não há hosting nem custos de servidor. Os vídeos são transmitidos a partir do Internet Archive.

## Build
```bash
export LUMIN_KEYSTORE=/caminho/lumin-filmes.jks LUMIN_KEYSTORE_PASS=...   # sem isto assina com a chave debug
./gradlew assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```
Usa sempre a mesma keystore. Com outra chave, as atualizações não instalam por cima da versão anterior.

## Adicionar filmes
Edita `catalog.json` (filmes que tocam no player). A interface está em `web/index.template.html`.

## Token TMDB
Põe `tmdb.token=eyJ...` em `local.properties` (ou a variável `TMDB_TOKEN`). O build injeta-o na APK, e o token nunca vai para o git.
Atenção: a chave atual é de uso pessoal/não comercial. Antes de distribuir publicamente, pede aprovação ao TMDB (API for Business).
