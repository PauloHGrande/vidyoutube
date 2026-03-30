# YouTube Downloader

Aplicação web em Java (Spring Boot) para baixar vídeos e playlists do YouTube usando o [yt-dlp](https://github.com/yt-dlp/yt-dlp).

---

## Requisitos

| Ferramenta | Versão mínima | Instalação |
|---|---|---|
| Java | 17+ | [Baixar](https://adoptium.net/) |
| Maven | 3.8+ | [Baixar](https://maven.apache.org/) |
| yt-dlp | 2026.x | `pip install yt-dlp` |
| Node.js | 20+ | [Baixar](https://nodejs.org/) |
| ffmpeg | qualquer | `winget install ffmpeg` |
| Firefox | qualquer | [Baixar](https://www.mozilla.org/pt-BR/firefox/) — logado no YouTube |

---

## Instalação

### 1. Clone ou baixe o projeto

```bash
cd C:\Git\youtube
```

### 2. Instale as dependências do sistema

```bash
# yt-dlp
pip install "yt-dlp[default]"

# ffmpeg
winget install ffmpeg

# Node.js (via winget)
winget install OpenJS.NodeJS
```

### 3. Compile o projeto

```bash
mvn package -q
```

---

## Como rodar

```bash
java -jar target/youtube-downloader.jar
```

Acesse no navegador: **http://localhost:8080**

---

## Como usar

### Vídeo único
1. Cole o link do vídeo no campo superior
2. Escolha o formato desejado
3. Selecione **Firefox** no seletor de navegador
4. Clique em **Baixar vídeo**
5. Quando concluído, o arquivo baixa automaticamente

### Playlist completa
1. Cole o link da playlist no campo inferior
2. Escolha o formato
3. Selecione **Firefox** no seletor de navegador
4. Clique em **Baixar playlist**
5. Aguarde — todos os vídeos são baixados e compactados em um `.zip`

> **Importante:** Certifique-se de estar logado no YouTube no Firefox antes de baixar.

---

## Formatos disponíveis

| Opção | Descrição |
|---|---|
| Melhor qualidade | MP4 com melhor vídeo + áudio (padrão) |
| MP4 (vídeo+áudio) | Força container MP4 com merge |
| Somente MP3 | Extrai apenas o áudio em MP3 |
| Menor tamanho | Menor resolução disponível |

---

## Arquivos baixados

Os arquivos ficam salvos em:

```
C:\Git\youtube\downloads\
```

Playlists são salvas em subpastas e compactadas em `.zip`:

```
downloads\
  Nome da Playlist\
    01 - Video Um.mp4
    02 - Video Dois.mp4
  Nome da Playlist.zip
```

---

## Iniciar com o Windows

Para o servidor subir automaticamente no login:

```bash
schtasks /create /tn "YouTubeDownloader" /tr "C:\Git\youtube\start.bat" /sc onlogon /rl highest /f
```

Para rodar manualmente sem reiniciar:

```bash
schtasks /run /tn "YouTubeDownloader"
```

Para remover:

```bash
schtasks /delete /tn "YouTubeDownloader" /f
```

---

## Solução de problemas

| Erro | Causa | Solução |
|---|---|---|
| `Signature solving failed` | Node.js não encontrado pelo yt-dlp | Instale Node.js 20+ e reinicie o terminal |
| `Sign in to confirm you're not a bot` | Sem autenticação | Use Firefox logado no YouTube |
| `Could not copy Chrome cookie database` | Chrome bloqueia acesso aos cookies | Use Firefox em vez de Chrome/Edge |
| `ffmpeg not found` | ffmpeg não instalado | `winget install ffmpeg` |
| `HTTP Error 429` | Muitas requisições ao YouTube | Aguarde alguns minutos e tente novamente |
| Vídeo sem áudio | ffmpeg ausente na hora do download | Instale ffmpeg e baixe novamente |
