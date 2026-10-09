package dev.halloween;

public enum Role {
	NONE,
	/** Michael Myers. */
	SHAPE,
	CIVILIAN,
	/** A civilian back in the match after escaping or dying: can't escape, carries a baton and a flashlight. */
	DEPUTY,
	/** The Runt: Michael's small horror, on his side, frightens and holds but can't kill (see game.Runt). */
	RUNT;

	public static Role byId(int id) {
		Role[] values = values();
		return id >= 0 && id < values.length ? values[id] : NONE;
	}

	/** On the civilians' side: can be hunted, locks doors, climbs windows, fights back. */
	public boolean survivor() {
		return this == CIVILIAN || this == DEPUTY;
	}
}
