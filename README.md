# TV Remote – assistência remota para Android TV / TV Box

- `server/` relay WebSocket + painel web (Node.js). Deploy via Dockerfile (Easypanel). Variáveis: `TOKEN` (obrigatório), `PORT` (8080), `DATA_DIR` (/data).
- `agent/` app Android (minSdk 24). Build: `SERVER=https://dominio TOKEN=... APP_NAME="Nome" ./agent/build.sh` (requer aapt, dalvik-exchange, zipalign, apksigner, JDK).
- O APK pronto fica em `server/public/agent.apk` e é servido em `https://dominio/agent.apk`.

## Na TV do cliente
1. Instalar o APK (app Downloader: `dominio/app`).
2. Abrir o app e ativar: Acessibilidade, Instalar apps desconhecidos, Ver a tela.
3. Pronto: aparece no painel `https://dominio`.
