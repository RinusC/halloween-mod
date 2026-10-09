package dev.halloween.test;

/**
 * HW_ONLY=map,bots (comma separated) runs just those client tests; unset runs them all. Each test asks here
 * first, so this is also where it is set up: no Runt in its matches unless the test is about him (an extra
 * bot would be the "@p" of the commands the tests use).
 */
final class TestFilter {
	private TestFilter() {
	}

	static boolean skip(String name) {
		boolean skip = skipped(name);
		if (!skip) {
			dev.halloween.HwConfig.get().runtEnabled = name.equals("runt");
		}
		return skip;
	}

	private static boolean skipped(String name) {
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
