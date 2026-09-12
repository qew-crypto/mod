package com.aimod;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes AI-returned mute durations to a short server-friendly form (10m, 3h, 7d).
 * If the AI returns a range ("30m-1h", "от 1 до 3 часов"), the minimum value is used.
 */
public final class Durations {
	private static final Pattern TOKEN = Pattern.compile("(\\d+)\\s*([a-zA-Zа-яА-Я]*)");

	private Durations() {}

	public static String normalizeMinimum(String raw, String fallback) {
		if (raw == null || raw.isBlank()) return fallback;
		String s = raw.toLowerCase(Locale.ROOT).trim();
		Matcher m = TOKEN.matcher(s);
		long bestSeconds = Long.MAX_VALUE;
		String best = null;
		while (m.find()) {
			long amount;
			try {
				amount = Long.parseLong(m.group(1));
			} catch (NumberFormatException e) {
				continue;
			}
			if (amount <= 0) continue;
			String unit = unitOf(m.group(2), s);
			long seconds = amount * secondsPerUnit(unit);
			if (seconds < bestSeconds) {
				bestSeconds = seconds;
				best = amount + unit;
			}
		}
		return best != null ? best : fallback;
	}

	/**
	 * Fallback: if the model returned a rule number but no duration, take the duration
	 * from the rules file line "1.2 | 1h | Любые оскорбления игроков".
	 */
	public static String fromRules(String rules, String rule, String fallback) {
		if (rules == null || rule == null || rule.isBlank()) return fallback;
		String needle = rule.trim();
		for (String line : rules.split("\\R")) {
			String t = line.trim();
			if (t.isEmpty() || t.startsWith("#")) continue;
			String[] parts = t.split("\\|");
			if (parts.length < 2) continue;
			if (!parts[0].trim().equalsIgnoreCase(needle)) continue;
			String d = normalizeMinimum(parts[1].trim(), "");
			if (!d.isBlank()) return d;
		}
		return fallback;
	}

	private static String unitOf(String token, String whole) {
		String t = token == null ? "" : token.trim();
		if (t.isEmpty()) {
			if (whole.contains("день") || whole.contains("дн") || whole.contains("сут") || whole.contains("day")) return "d";
			if (whole.contains("час") || whole.contains("hour")) return "h";
			if (whole.contains("сек") || whole.contains("sec")) return "s";
			return "m";
		}
		char c = t.charAt(0);
		if (t.startsWith("сут") || t.startsWith("дн") || t.startsWith("де") || c == 'd') return "d";
		if (t.startsWith("нед") || t.startsWith("w")) return "w";
		if (t.startsWith("час") || t.startsWith("ч") || c == 'h') return "h";
		if (t.startsWith("сек") || t.startsWith("с") || c == 's') return "s";
		if (t.startsWith("мин") || t.startsWith("м") || c == 'm') return "m";
		return "m";
	}

	private static long secondsPerUnit(String unit) {
		return switch (unit) {
			case "s" -> 1L;
			case "h" -> 3600L;
			case "d" -> 86400L;
			case "w" -> 604800L;
			default -> 60L;
		};
	}
}
