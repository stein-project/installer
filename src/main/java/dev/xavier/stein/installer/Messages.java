/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.installer;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Installer texts in English (default), Spanish and Portuguese: window, log and errors. */
public final class Messages {

    public enum Lang {
        EN("English"),
        ES("Español"),
        PT("Português");

        public final String label;

        Lang(String label) {
            this.label = label;
        }

        public static Lang parse(String code) {
            switch (code.toLowerCase(Locale.ROOT)) {
                case "es":
                    return ES;
                case "pt":
                case "pt-br":
                case "pt_br":
                    return PT;
                default:
                    return EN;
            }
        }
    }

    private static final Map<String, String[]> TEXT = new HashMap<>();

    private static void put(String key, String en, String es, String pt) {
        TEXT.put(key, new String[] {en, es, pt});
    }

    static {
        // ---- installation (log and errors) ----
        put(
                "start",
                "Installing Stein Loader %s in %s",
                "Instalando Stein Loader %s en %s",
                "Instalando o Stein Loader %s em %s");
        put(
                "update",
                "Updating Stein Loader %s to %s in %s",
                "Actualizando Stein Loader %s a %s en %s",
                "Atualizando o Stein Loader %s para %s em %s");
        put(
                "versions",
                "Components: Stein Bridge %s",
                "Componentes: Stein Bridge %s",
                "Componentes: Stein Bridge %s");
        put(
                "found",
                "Minecraft 1.8.9 found and verified.",
                "Minecraft 1.8.9 encontrado y verificado.",
                "Minecraft 1.8.9 encontrado e conferido.");
        put(
                "howTo",
                " Open the launcher, play Minecraft 1.8.9 once (it downloads the game) and run the"
                        + " installer again.",
                " Abre el launcher, juega Minecraft 1.8.9 una vez (así se descarga el juego) y"
                        + " vuelve a ejecutar el instalador.",
                " Abra o launcher, jogue o Minecraft 1.8.9 uma vez (ele baixa o jogo) e rode o"
                        + " instalador de novo.");
        put(
                "notFound",
                "Minecraft 1.8.9 is not in %s.",
                "Minecraft 1.8.9 no está en %s.",
                "O Minecraft 1.8.9 não está em %s.");
        put(
                "missingLibs",
                "Minecraft 1.8.9 libraries are missing in %s.",
                "Faltan bibliotecas de Minecraft 1.8.9 en %s.",
                "Faltam bibliotecas do Minecraft 1.8.9 em %s.");
        put(
                "badJar",
                "The 1.8.9.jar in %s is not Mojang's original (different sha1).",
                "El 1.8.9.jar de %s no es el original de Mojang (sha1 distinto).",
                "O 1.8.9.jar de %s não é o original da Mojang (outro sha1).");
        put(
                "javaOk",
                "Java 25 already installed.",
                "Java 25 ya instalado.",
                "Java 25 já instalado.");
        put(
                "javaCopy",
                "Copying Java 25 to %s...",
                "Copiando Java 25 a %s...",
                "Copiando o Java 25 para %s...");
        put(
                "javaDownload",
                "Downloading Java 25 for your system (%s, about 50 MB)...",
                "Descargando Java 25 para tu sistema (%s, unos 50 MB)...",
                "Baixando o Java 25 do seu sistema (%s, uns 50 MB)...");
        put(
                "javaBad",
                "The downloaded Java (%s) does not match what this installer expects. Nothing was"
                        + " changed.",
                "El Java descargado (%s) no coincide con lo que este instalador espera. No se"
                        + " cambió nada.",
                "O Java baixado (%s) nao confere com o que este instalador espera. Nada foi"
                        + " alterado.");
        put(
                "javaNoChecksum",
                "This installer has no checksum for the Java runtime (%s), so the download was"
                        + " refused. Nothing was changed.",
                "Este instalador no tiene la suma de verificación del runtime de Java (%s), así que"
                        + " se rechazó la descarga. No se cambió nada.",
                "Este instalador não tem a soma de verificação do runtime do Java (%s); o download"
                        + " foi recusado. Nada foi alterado.");
        put(
                "javaFail",
                "Could not download Java from %s (%s). Check your connection and run the installer"
                        + " again - nothing was changed.",
                "No se pudo descargar Java desde %s (%s). Revisa tu conexión y vuelve a ejecutar el"
                        + " instalador: no se cambió nada.",
                "Nao deu para baixar o Java de %s (%s). Confira a internet e rode o instalador de"
                        + " novo - nada foi alterado.");
        put(
                "patching",
                "Patching Minecraft 1.8.9 (the jar from your installation)...",
                "Aplicando el parche a Minecraft 1.8.9 (el jar de tu instalación)...",
                "Aplicando o patch no Minecraft 1.8.9 (o jar da sua instalação)...");
        put(
                "patchFail",
                "Patching %s failed (code %d):%n%s",
                "El parche de %s falló (código %d):%n%s",
                "O patch de %s falhou (código %d):%n%s");
        put("patched", "  %s: ok", "  %s: ok", "  %s: ok");
        put(
                "execMarkFail",
                "warning: could not mark %s as executable (%s)",
                "aviso: no se pudo marcar %s como ejecutable (%s)",
                "aviso: não consegui marcar %s como executável (%s)");
        put(
                "version",
                "Libraries and version %s...",
                "Bibliotecas y versión %s...",
                "Bibliotecas e versão %s...");
        put(
                "launcherNew",
                "Launcher: official Minecraft Launcher (new format).",
                "Launcher: Minecraft Launcher oficial (formato nuevo).",
                "Launcher: Minecraft Launcher oficial (formato novo).");
        put(
                "launcherLegacy",
                "Launcher: %s (old format) - version and profile written for it too.",
                "Launcher: %s (formato antiguo) - versión y perfil escritos también para él.",
                "Launcher: %s (formato antigo) - versão e perfil gravados também para ele.");
        put("profile", "Profile \"%s\" in %s", "Perfil \"%s\" en %s", "Perfil \"%s\" em %s");
        put(
                "done",
                "Done. In the launcher, choose the profile \"%s\" and click Play.",
                "Listo. En el launcher, elige el perfil \"%s\" y haz clic en Jugar.",
                "Pronto. No launcher, escolha o perfil \"%s\" e clique em Jogar.");
        // The mods, downloaded from the published list (Mods.java).
        put("modsTitle", "Mods (optional)", "Mods (opcionales)", "Mods (opcionais)");
        put(
                "modsHint",
                "Downloaded and kept up to date by this installer.",
                "Descargados y mantenidos al día por este instalador.",
                "Baixados e mantidos em dia por este instalador.");
        put("modDownload", "Downloading %s %s...", "Descargando %s %s...", "Baixando %s %s...");
        put(
                "modOk",
                "%s %s is already up to date.",
                "%s %s ya está al día.",
                "%s %s já está em dia.");
        put("modRemoved", "%s removed.", "%s quitado.", "%s removido.");
        put(
                "modBadSum",
                "%s: the downloaded file does not match its checksum; it was discarded.",
                "%s: el archivo descargado no coincide con su suma de verificación; fue"
                        + " descartado.",
                "%s: o arquivo baixado não confere com a soma de verificação; foi descartado.");
        put(
                "modTooNew",
                "%s needs Stein Loader %s or newer; it was not installed.",
                "%s necesita Stein Loader %s o más nuevo; no se instaló.",
                "%s precisa do Stein Loader %s ou mais novo; não foi instalado.");
        put(
                "modFailed",
                "%s could not be downloaded: %s",
                "No se pudo descargar %s: %s",
                "Não consegui baixar o %s: %s");
        put(
                "modsDone",
                "Mods up to date (%s file(s) changed).",
                "Mods al día (%s archivo(s) cambiados).",
                "Mods em dia (%s arquivo(s) alterados).");
        put(
                "modsNothing",
                "Mods were already up to date.",
                "Los mods ya estaban al día.",
                "Os mods já estavam em dia.");
        put(
                "modsOffline",
                "No connection to the mod list: the mods folder was left as it is.",
                "Sin conexión con la lista de mods: la carpeta de mods se quedó como estaba.",
                "Sem conexão com a lista de mods: a pasta de mods ficou como estava.");
        put(
                "modsFailed",
                "The mods were not updated: %s",
                "Los mods no se actualizaron: %s",
                "Os mods não foram atualizados: %s");
        put(
                "removing",
                "Removing Stein Loader from %s",
                "Quitando Stein Loader de %s",
                "Removendo o Stein Loader de %s");
        put(
                "profileRemoved",
                "Profile removed from %s",
                "Perfil quitado de %s",
                "Perfil removido de %s");
        put(
                "removed",
                "Done. Worlds, settings and mods were kept.",
                "Listo. Tus mundos, ajustes y mods se conservaron.",
                "Pronto. Mundos, configurações e mods ficaram.");
        put(
                "incomplete",
                "Incomplete installer: missing %s",
                "Instalador incompleto: falta %s",
                "Instalador incompleto: falta %s");

        // ---- window ----
        put(
                "windowTitle",
                "Stein Loader %s - Installer",
                "Stein Loader %s - Instalador",
                "Stein Loader %s - Instalador");
        put("headerTitle", "Installer", "Instalador", "Instalador");
        put(
                "headerSub",
                "Stein Loader %s (alpha) for Minecraft 1.8.9",
                "Stein Loader %s (alpha) para Minecraft 1.8.9",
                "Stein Loader %s (alpha) para o Minecraft 1.8.9");
        put("continue", "Continue", "Continuar", "Continuar");
        put("folder", "Minecraft folder:", "Carpeta de Minecraft:", "Pasta do Minecraft:");
        put("browse", "Browse...", "Buscar...", "Procurar...");
        put(
                "chooser",
                "Minecraft folder (.minecraft)",
                "Carpeta de Minecraft (.minecraft)",
                "Pasta do Minecraft (.minecraft)");
        put("components", "Components:", "Componentes:", "Componentes:");
        put(
                "bridge",
                "Stein Bridge - Java 25, LWJGL 3 and optimizations",
                "Stein Bridge - Java 25, LWJGL 3 y optimizaciones",
                "Stein Bridge - Java 25, LWJGL 3 e otimizações");
        put(
                "loader",
                "Stein Loader - ultra-lightweight mod loader",
                "Stein Loader - cargador de mods ultraligero",
                "Stein Loader - carregador de mods ultraleve");
        put("memory", "Game memory:", "Memoria del juego:", "Memória do jogo:");
        put("install", "Install", "Instalar", "Instalar");
        put("updateButton", "Update", "Actualizar", "Atualizar");
        put("uninstall", "Uninstall", "Desinstalar", "Desinstalar");
        put("finish", "Finish", "Finalizar", "Finalizar");
        put("openMinecraft", "Open .minecraft", "Abrir .minecraft", "Abrir .minecraft");
        put(
                "ready",
                "Choose the Minecraft folder (the ones found are in the list) and click Install.",
                "Elige la carpeta de Minecraft (las encontradas están en la lista) y haz clic en"
                        + " Instalar.",
                "Escolha a pasta do Minecraft (as encontradas estão na lista) e clique em"
                        + " Instalar.");
        put(
                "detected",
                "Found %d Minecraft 1.8.9 folders - pick one in the \"Minecraft folder\" list:",
                "Se encontraron %d carpetas de Minecraft 1.8.9 - elige una en la lista \"Carpeta de"
                        + " Minecraft\":",
                "Encontradas %d pastas do Minecraft 1.8.9 - escolha uma na lista \"Pasta do"
                        + " Minecraft\":");
        put(
                "installedHere",
                "Stein Loader %s is installed in this folder. Update keeps your worlds, settings"
                        + " and mods.",
                "Stein Loader %s está instalado en esta carpeta. Actualizar conserva tus mundos,"
                        + " ajustes y mods.",
                "O Stein Loader %s está instalado nesta pasta. Atualizar mantém seus mundos,"
                        + " configurações e mods.");
        put(
                "launcherOpen",
                "The Minecraft launcher is open. Close it before installing: when it closes, it may"
                        + " erase the new profile.",
                "El launcher de Minecraft está abierto. Ciérralo antes de instalar: al cerrarse,"
                        + " puede borrar el perfil nuevo.",
                "O launcher do Minecraft está aberto. Feche-o antes de instalar: ao fechar, ele"
                        + " pode apagar o perfil novo.");
        put(
                "gameOpen",
                "Minecraft is running from this folder. Close the game and try again: nothing was"
                        + " changed.",
                "Minecraft está abierto desde esta carpeta. Cierra el juego y vuelve a intentarlo:"
                        + " no se cambió nada.",
                "O Minecraft está aberto por esta pasta. Feche o jogo e tente de novo: nada foi"
                        + " alterado.");
        put("installing", "Installing...", "Instalando...", "Instalando...");
        put("updating", "Updating...", "Actualizando...", "Atualizando...");
        put("uninstalling", "Uninstalling...", "Desinstalando...", "Desinstalando...");
        put(
                "installedOk",
                "Stein Loader installed!",
                "¡Stein Loader instalado!",
                "Stein Loader instalado!");
        put(
                "updatedOk",
                "Stein Loader updated!",
                "¡Stein Loader actualizado!",
                "Stein Loader atualizado!");
        put("inFolder", "Folder: %s", "Carpeta: %s", "Pasta: %s");
        put(
                "removedOk",
                "Stein Loader uninstalled.",
                "Stein Loader desinstalado.",
                "Stein Loader desinstalado.");
        put(
                "nextStep",
                "In the launcher, choose the profile \"%s\" and click Play.",
                "En el launcher, elige el perfil \"%s\" y haz clic en Jugar.",
                "No launcher, escolha o perfil \"%s\" e clique em Jogar.");
        put("error", "Error: %s", "Error: %s", "Erro: %s");
        put("cliUnknown", "unknown option: %s", "opción desconocida: %s", "opção desconhecida: %s");
    }

    private final Lang lang;

    private Messages(Lang lang) {
        this.lang = lang;
    }

    public static Messages of(Lang lang) {
        return new Messages(lang);
    }

    public Lang lang() {
        return lang;
    }

    public String t(String key, Object... args) {
        String[] v = TEXT.get(key);
        if (v == null) {
            return key;
        }
        return args.length == 0 ? v[lang.ordinal()] : String.format(v[lang.ordinal()], args);
    }
}
