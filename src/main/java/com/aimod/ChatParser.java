package com.aimod;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a chat line into (nick, message).
 *
 * The old version only understood "&lt;Nick&gt; text" / "Nick: text" and therefore never matched
 * real server formats like:
 *
 *   PLAYER ywthshfejsgec -> спасибооо
 *   HELPER Crypa228 Седой семпай -> ywthshfejsgec напиши в глобал чат
 *   HELPER ~Djast SUPPORT -> Crypa228 лошок
 *   [VIP] Nick: text
 *   Nick > text
 *
 * AUTO mode: split on the first separator (arrow / colon / angle bracket), then pick the nick
 * from the left part by skipping rank words (ALL-CAPS tokens) and display titles (non-latin words).
 */
public final class ChatParser {

/**
	 * Any arrow-like / pointer glyph used by chat plugins between the sender block and the message.
	 * Covers → ⇢ ⇝ ➤ ➔ ↪ » › ▶ ▸ ⮞ and the rest of the Unicode arrow blocks,
	 * so unusual server glyphs no longer break parsing.
	 */
	private static final Pattern ARROW = Pattern.compile(
			"[\\u2190-\\u21FF\\u2794-\\u27BF\\u27F0-\\u27FF\\u2900-\\u297F\\u2B00-\\u2B11\\u2B95\\u2B9E"
					+ "\\u00BB\\u203A\\u2023\\u25B6\\u25B8\\u25BA\\u25BB\\u276F\\u00AB]");

	/** Plain-text separators, checked after arrows. */
	private static final String[] SEPARATORS = { "->", "=>", ">>", ":", "|", ">" };

	/** A plausible Minecraft nick, optionally prefixed by ~ (used by some ranks). */
	private static final Pattern NICK = Pattern.compile("^[~*]?([A-Za-z0-9_]{3,16})$");

	/** Lines that are clearly not player chat. */
	private static final Pattern SYSTEM_LINE = Pattern.compile(
			"(?i)^\\s*\\[?(ai moderator|server|сервер|info|инфо|announcement|оповещение)\\]?\\s*[:\\-]");

	public static final class Parsed {
		public final String nick;
		public final String text;

		public Parsed(String nick, String text) {
			this.nick = nick;
			this.text = text;
		}
	}

	private ChatParser() {}

	public static String stripFormatting(String s) {
		if (s == null) return "";
		// § color codes, & color codes and invisible / decorative characters
		String out = s.replaceAll("\u00A7[0-9A-FK-ORa-fk-or]", "");
		out = out.replaceAll("&[0-9A-FK-ORa-fk-or](?=\\S)", "");
		out = out.replaceAll("[\u2500-\u257F\u2580-\u259F]", " ");
		return out.replaceAll("\\s+", " ").trim();
	}

	public static Optional<Parsed> parse(String rawLine, Config cfg) {
		String line = stripFormatting(rawLine);
		if (line.isBlank()) return Optional.empty();
		if (SYSTEM_LINE.matcher(line).find()) return Optional.empty();

		// 1) user-defined regex always wins if it is set and matches
		if (cfg.chatRegex != null && !cfg.chatRegex.isBlank()) {
			try {
				Matcher m = Pattern.compile(cfg.chatRegex).matcher(line);
				if (m.find() && m.groupCount() >= 2) {
					String nick = cleanNick(m.group(1));
					String text = m.group(2) == null ? "" : m.group(2).trim();
					if (!nick.isBlank() && !text.isBlank()) return Optional.of(new Parsed(nick, text));
				}
			} catch (Exception e) {
				ModLog.debug("Плохой chatRegex, используется авто-режим: " + e.getMessage());
			}
			if ("regex".equalsIgnoreCase(cfg.chatParseMode)) return Optional.empty();
		}

		if ("regex".equalsIgnoreCase(cfg.chatParseMode)) return Optional.empty();

		// 2) auto mode
		return auto(line);
	}

	private static Optional<Parsed> auto(String line) {
		// 1) arrow-like glyphs (→, ⇢, ➤, » …) — the most common server format
		Matcher arrow = ARROW.matcher(line);
		while (arrow.find()) {
			if (arrow.start() == 0) continue;
			String left = line.substring(0, arrow.start()).trim();
			String right = line.substring(arrow.end()).trim();
			if (left.isBlank() || right.isBlank()) continue;
			String nick = pickNick(left);
			if (nick != null) return Optional.of(new Parsed(nick, right));
		}

		// 2) plain-text separators
		for (String sep : SEPARATORS) {
			int idx = line.indexOf(sep);
			while (idx > 0) {
				String left = line.substring(0, idx).trim();
				String right = line.substring(idx + sep.length()).trim();
				if (!right.isBlank() && !left.isBlank()) {
					String nick = pickNick(left);
					if (nick != null) return Optional.of(new Parsed(nick, right));
				}
				idx = line.indexOf(sep, idx + sep.length());
			}
		}
		// "<Nick> text" without any of the separators above
		Matcher m = Pattern.compile("^<([A-Za-z0-9_]{3,16})>\\s*(.+)$").matcher(line);
		if (m.find()) return Optional.of(new Parsed(m.group(1), m.group(2).trim()));
		return Optional.empty();
	}

	/**
	 * Picks the real nick out of a sender block such as
	 * "HELPER Crypa228 Седой семпай", "HELPER ~Djast SUPPORT", "[VIP] Nick", "PLAYER Nick".
	 */
	private static String pickNick(String left) {
		String cleaned = left.replaceAll("\\[[^\\]]*\\]", " ") // [VIP], [Прем]
				.replaceAll("\\([^\\)]*\\)", " ")
				.replaceAll("[\\p{So}\\p{Cn}]", " ") // emoji / rank icons
				.trim();

		List<String> candidates = new ArrayList<>();
		for (String token : cleaned.split("[\\s\u00B7•,]+")) {
			String t = token.trim();
			if (t.isEmpty()) continue;
			Matcher m = NICK.matcher(t);
			if (!m.matches()) continue;
			String nick = m.group(1);
			if (isRankWord(nick)) continue;
			candidates.add(nick);
		}
		if (candidates.isEmpty()) return null;
		// The nick is the first non-rank latin token in the sender block.
		return candidates.get(0);
	}

	/** ALL-CAPS tokens and well-known rank names are never nicks. */
	private static boolean isRankWord(String token) {
		String upper = token.toUpperCase(Locale.ROOT);
		if (token.equals(upper) && token.matches("[A-Z]{3,16}")) return true;
		return switch (upper) {
			case "PLAYER", "HELPER", "SUPPORT", "MODER", "MODERATOR", "ADMIN", "OWNER", "CURATOR",
					"VIP", "PREMIUM", "DELUXE", "ELITE", "LEGEND", "GUEST", "CHAT", "GLOBAL",
					"LOCAL", "CLAN", "MSG", "NEWS", "SERVER", "YOU" -> true;
			default -> false;
		};
	}

	private static String cleanNick(String raw) {
		if (raw == null) return "";
		String n = raw.trim();
		while (!n.isEmpty() && (n.charAt(0) == '~' || n.charAt(0) == '*' || n.charAt(0) == '<')) {
			n = n.substring(1);
		}
		if (n.endsWith(">")) n = n.substring(0, n.length() - 1);
		return n.trim();
	}
}
