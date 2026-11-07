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
Edita o array `FILMS` em `index.html` (ou `catalog.json`). Regra: só entram filmes cujos autores (realizador, argumentista, compositor) morreram há mais de 70 anos, ou que tenham licença CC. Coloca sempre os créditos e a licença.
