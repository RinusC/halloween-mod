package dev.halloween.test;

/** HW_ONLY=map,bots (comma separated) runs just those client tests; unset runs them all. */
final class TestFilter {
	private TestFilter() {
	}

	static boolean skip(String name) {
		String only = System.getenv("HW_ONLY");
		if (only == null || only.isBlank()) {
			return false;
		}
		for (String s : only.split(",")) {
			if (s.trim().equalsIgnoreCase(name)) {
				return false;
			}
		}
		return true;
	}
}
