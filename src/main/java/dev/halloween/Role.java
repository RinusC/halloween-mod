package dev.halloween;

public enum Role {
	NONE,
	/** Michael Myers. */
	SHAPE,
	CIVILIAN;

	public static Role byId(int id) {
		Role[] values = values();
		return id >= 0 && id < values.length ? values[id] : NONE;
	}
}
