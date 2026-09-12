package com.aimod;

/** A single AI-detected, mute-only violation awaiting confirmation. */
public class Violation {
	public final String nick;
	public final String message;
	public final String rule;
	public final String reason;
	public final String duration;
	public final long createdAt;

	public Violation(String nick, String message, String rule, String reason, String duration) {
		this.nick = nick;
		this.message = message;
		this.rule = rule;
		this.reason = reason;
		this.duration = duration;
		this.createdAt = System.currentTimeMillis();
	}
}
