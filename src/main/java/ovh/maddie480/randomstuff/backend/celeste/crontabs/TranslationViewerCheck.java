package ovh.maddie480.randomstuff.backend.celeste.crontabs;

import org.apache.commons.io.IOUtils;
import org.apache.commons.text.StringEscapeUtils;
import ovh.maddie480.randomstuff.backend.SecretConstants;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class TranslationViewerCheck {
    private record Check(String program, String language, String expected) {
    }

    public static void main() throws IOException {
        for (Check check : Arrays.asList(
                new Check("everest", "", "{big}Thank you ((player))!{/big}{n}But our {#ff1144}((sid)){#} is on another mountain."),
                new Check("everest", "Brazilian+Portuguese", "{big}Obrigado ((player))!{/big}{n}Mas nosso {#ff1144}((sid)){#} está em outra montanha."),
                new Check("everest", "French", "{big}Merci ((player)) !{/big}{n}Mais {#ff1144}((sid)){#} est dans une autre montagne.{n}{n}(Cette map n'existe plus.)"),
                new Check("everest", "German", "{big}Entschuldigung ((player)),{n}{/big}aber {#ff1144}((sid)){#} konnte nicht gefunden werden."),
                new Check("everest", "Japanese", "{big}おかえり((player))！{/big}{n}でも{#ff1144}((sid)){#}はどこか別の山に行ってしまったみたいだね。"),
                new Check("everest", "Korean", "{big}고마워요, ((player))!{/big}{n}하지만 {#ff1144}((sid)){#}은(는) 다른 산에 있어요"),
                new Check("everest", "Polish", "{big}Dziękuję ((player))!{/big}{n}Ale niestety {#ff1144}((sid)){#} jest na innej górze."),
                new Check("everest", "Russian", "{big}Спасибо, ((player))!{/big}{n}Но {#ff1144}((sid)){#} находится на другой горе."),
                new Check("everest", "Simplified+Chinese", "{big}谢谢你((player))！{/big}{n}不过我们的 {#ff1144}((sid)){#} 在另一座山。"),

                new Check("olympus", "", "Olympus is currently busy with something else."),
                new Check("olympus", "fr", "Olympus est actuellement occupé avec autre chose."),
                new Check("olympus", "zh", "Olympus 当前正忙于其他任务。"),

                new Check("cu2", "", "Return to Lobby"),
                new Check("cu2", "Brazilian+Portuguese", "Voltar ao lobby"),
                new Check("cu2", "French", "Retourner au Lobby"),
                new Check("cu2", "German", "Zurück zur Lobby"),
                new Check("cu2", "Japanese", "ロビーにもどる"),
                new Check("cu2", "Korean", "로비로 돌아가기"),
                new Check("cu2", "Polish", "Powrót do Lobby"),
                new Check("cu2", "Russian", "Вернуться в Лобби"),
                new Check("cu2", "Simplified+Chinese", "返回大厅"),

                new Check("evm", "", "Extended Variant Mode"),
                new Check("evm", "Brazilian+Portuguese", "Modo Variáveis Estendidas"),
                new Check("evm", "French", "Mode Variante étendu"),
                new Check("evm", "German", "Vertikale Geschwindigkeit"),
                new Check("evm", "Japanese", "拡張バリアント"),
                new Check("evm", "Korean", "확장된 별형 모드"),
                new Check("evm", "Polish", "Tryb Dodatkowych Modyfikacji"),
                new Check("evm", "Russian", "Расширенные Вариации"),
                new Check("evm", "Simplified+Chinese", "拓展异变"),
                new Check("evm", "Spanish", "Modo De variantes Extendidas")
        )) {
            HttpURLConnection u = ConnectionUtils.openConnectionWithTimeout("https://maddie480.ovh/celeste/translation-viewer?program=" + check.program + "&language=" + check.language);
            u.setInstanceFollowRedirects(true);
            try (InputStream is = ConnectionUtils.connectionToInputStream(u)) {
                if (!IOUtils.toString(is, StandardCharsets.UTF_8)
                        .contains(StringEscapeUtils.escapeHtml4(check.expected))) {
                    throw new IOException("Could not find expected string in " + check.program + " in language " + check.language);
                }
            }
        }
    }

    public static void triggerRefresh() throws IOException {
        HttpURLConnection connection = ConnectionUtils.openConnectionWithTimeout(
                "https://maddie480.ovh/celeste/translation-viewer-reload?key=" + SecretConstants.RELOAD_SHARED_SECRET);
        connection.setReadTimeout(600_000);

        if (connection.getResponseCode() != 200) {
            throw new IOException("Housekeeping arbitrary mod app failed!");
        }
    }
}
