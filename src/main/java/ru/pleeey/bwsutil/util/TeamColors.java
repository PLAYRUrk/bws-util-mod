package ru.pleeey.bwsutil.util;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.scores.PlayerTeam;

import java.util.List;
import java.util.Locale;

/**
 * Maps a BedWars team onto the dye colour of its bed.
 *
 * <p>Servers are inconsistent about how they express team identity: some set the scoreboard
 * team colour, some only style the display name or the prefix, some encode it in the raw team
 * name. Each resolution path here handles one of those cases, ordered from most to least
 * reliable.</p>
 *
 * <p>Shared by the overlay (team ↔ bed matching) and the match tracker (chat parsing), which
 * is why it lives outside both.</p>
 */
public final class TeamColors {

    /**
     * Squared RGB distance beyond which a colour match is considered noise rather than a team
     * colour. Keeps neutral greys from snapping onto an arbitrary dye.
     */
    private static final int MAX_COLOR_DISTANCE_SQ = 14_000;

    private TeamColors() {}

    /** Full resolution chain for a scoreboard team; {@code null} when nothing matches. */
    public static DyeColor ofTeam(PlayerTeam team) {
        if (team == null) return null;

        DyeColor byFormatting = fromChatFormatting(team.getColor());
        if (byFormatting != null) return byFormatting;

        DyeColor byDisplayStyle = fromTextColor(team.getDisplayName().getStyle().getColor());
        if (byDisplayStyle != null) return byDisplayStyle;

        DyeColor byPrefixStyle = fromTextColor(team.getPlayerPrefix().getStyle().getColor());
        if (byPrefixStyle != null) return byPrefixStyle;

        return fromName(team.getName());
    }

    public static DyeColor fromChatFormatting(ChatFormatting fmt) {
        if (fmt == null) return null;
        return switch (fmt) {
            case RED, DARK_RED       -> DyeColor.RED;
            case BLUE, DARK_BLUE     -> DyeColor.BLUE;
            case GREEN, DARK_GREEN   -> DyeColor.GREEN;
            case YELLOW              -> DyeColor.YELLOW;
            case AQUA, DARK_AQUA     -> DyeColor.CYAN;
            case WHITE               -> DyeColor.WHITE;
            case LIGHT_PURPLE        -> DyeColor.PINK;
            case DARK_PURPLE         -> DyeColor.PURPLE;
            case GOLD                -> DyeColor.ORANGE;
            case GRAY                -> DyeColor.LIGHT_GRAY;
            case DARK_GRAY           -> DyeColor.GRAY;
            case BLACK               -> DyeColor.BLACK;
            default                  -> null;
        };
    }

    /** Nearest dye by RGB distance, or {@code null} if nothing is close enough to be meaningful. */
    public static DyeColor fromTextColor(TextColor tc) {
        if (tc == null) return null;
        int rgb = tc.getValue() & 0x00FFFFFF;

        DyeColor best = null;
        int bestDist = Integer.MAX_VALUE;
        for (DyeColor dye : DyeColor.values()) {
            int c = dye.getMapColor().col;
            int dr = ((rgb >> 16) & 0xFF) - ((c >> 16) & 0xFF);
            int dg = ((rgb >> 8) & 0xFF) - ((c >> 8) & 0xFF);
            int db = (rgb & 0xFF) - (c & 0xFF);
            int dist = dr * dr + dg * dg + db * db;
            if (dist < bestDist) {
                bestDist = dist;
                best = dye;
            }
        }
        return bestDist <= MAX_COLOR_DISTANCE_SQ ? best : null;
    }

    /**
     * Colour keyword lookup for English and Russian text — team names, but also chat lines
     * such as "Red bed was destroyed" or «Кровать команды Красные разрушена».
     *
     * <p>Order matters: {@code lime} is checked before {@code green} and {@code lightblue}
     * before {@code blue}, otherwise the broader keyword would swallow the specific one.</p>
     */
    public static DyeColor fromName(String rawName) {
        if (rawName == null) return null;
        String n = rawName.toLowerCase(Locale.ROOT);

        if (n.contains("lime") || n.contains("салат")) return DyeColor.LIME;
        if (n.contains("lightblue") || n.contains("light blue") || n.contains("голуб")) return DyeColor.LIGHT_BLUE;
        if (n.contains("red") || n.contains("красн")) return DyeColor.RED;
        if (n.contains("blue") || n.contains("син")) return DyeColor.BLUE;
        if (n.contains("green") || n.contains("зелён") || n.contains("зелен")) return DyeColor.GREEN;
        if (n.contains("yellow") || n.contains("жёлт") || n.contains("желт")) return DyeColor.YELLOW;
        if (n.contains("teal") || n.contains("cyan") || n.contains("aqua") || n.contains("бирюз")) return DyeColor.CYAN;
        if (n.contains("white") || n.contains("бел")) return DyeColor.WHITE;
        if (n.contains("pink") || n.contains("розов")) return DyeColor.PINK;
        if (n.contains("gray") || n.contains("grey") || n.contains("сер")) return DyeColor.GRAY;
        if (n.contains("orange") || n.contains("оранж")) return DyeColor.ORANGE;
        if (n.contains("purple") || n.contains("фиолет")) return DyeColor.PURPLE;
        if (n.contains("black") || n.contains("чёрн") || n.contains("черн")) return DyeColor.BLACK;
        return null;
    }

    /**
     * Palette variants a server may use for the same nominal team colour — e.g. a "green" team
     * whose bed is actually lime wool.
     */
    public static List<DyeColor> alternatives(PlayerTeam team) {
        if (team == null) return List.of();
        ChatFormatting fmt = team.getColor();
        if (fmt == null) return List.of();
        return switch (fmt) {
            case GREEN, DARK_GREEN -> List.of(DyeColor.GREEN, DyeColor.LIME);
            case AQUA, DARK_AQUA   -> List.of(DyeColor.CYAN, DyeColor.LIGHT_BLUE);
            case BLUE, DARK_BLUE   -> List.of(DyeColor.BLUE, DyeColor.LIGHT_BLUE);
            case GRAY, DARK_GRAY   -> List.of(DyeColor.GRAY, DyeColor.LIGHT_GRAY);
            case WHITE             -> List.of(DyeColor.WHITE, DyeColor.LIGHT_GRAY);
            default                -> List.of();
        };
    }

    /** Opaque ARGB for HUD text, falling back to white when the team has no usable colour. */
    public static int displayArgb(PlayerTeam team) {
        if (team != null) {
            ChatFormatting fmt = team.getColor();
            if (fmt != null && fmt.getColor() != null) return 0xFF000000 | fmt.getColor();
        }
        return 0xFFFFFFFF;
    }

    /** Short uppercase label for a dye colour, used in the match log ("RED", "AQUA", …). */
    public static String shortLabel(DyeColor color) {
        if (color == null) return "?";
        return switch (color) {
            case LIGHT_BLUE -> "LBLUE";
            case LIGHT_GRAY -> "LGRAY";
            default -> color.getName().toUpperCase(Locale.ROOT);
        };
    }

    /** Opaque ARGB of a dye, for colouring match-log rows. */
    public static int dyeArgb(DyeColor color) {
        if (color == null) return 0xFFAAAAAA;
        return 0xFF000000 | (color.getTextureDiffuseColor() & 0x00FFFFFF);
    }
}
