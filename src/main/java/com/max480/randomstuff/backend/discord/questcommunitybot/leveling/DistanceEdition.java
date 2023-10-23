package com.max480.randomstuff.backend.discord.questcommunitybot.leveling;

import org.apache.commons.lang3.StringUtils;

/**
 * Pour calculer la distance d'édition entre 2 String :
 * combien de caractères je dois ajouter, supprimer ou remplacer pour passer d'une String à l'autre.
 */
public class DistanceEdition {
    public static int computeDistance(String a, String b) {
        return minDistance(StringUtils.stripAccents(a.toLowerCase()), StringUtils.stripAccents(b.toLowerCase()));
    }

    // source : https://www.programcreek.com/2013/12/edit-distance-in-java/
    private static int minDistance(String word1, String word2) {
        int len1 = word1.length();
        int len2 = word2.length();

        // len1+1, len2+1, because finally return dp[len1][len2]
        int[][] dp = new int[len1 + 1][len2 + 1];

        for (int i = 0; i <= len1; i++) {
            dp[i][0] = i;
        }

        for (int j = 0; j <= len2; j++) {
            dp[0][j] = j;
        }

        //iterate though, and check last char
        for (int i = 0; i < len1; i++) {
            char c1 = word1.charAt(i);
            for (int j = 0; j < len2; j++) {
                char c2 = word2.charAt(j);

                //if last two chars equal
                if (c1 == c2) {
                    //update dp value for +1 length
                    dp[i + 1][j + 1] = dp[i][j];
                } else {
                    int replace = dp[i][j] + 1;
                    int insert = dp[i][j + 1] + 1;
                    int delete = dp[i + 1][j] + 1;

                    int min = Math.min(replace, insert);
                    min = Math.min(delete, min);
                    dp[i + 1][j + 1] = min;
                }
            }
        }

        return dp[len1][len2];
    }
}
