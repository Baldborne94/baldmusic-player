import { describe, expect, it } from "vitest";
import { formatDuration, formatTotal } from "./format";

describe("format", () => {
  it("durate dei brani", () => {
    expect(formatDuration(215)).toBe("3:35");
    expect(formatDuration(3725)).toBe("1:02:05");
    expect(formatDuration(undefined)).toBe("–:––");
  });

  it("durate totali", () => {
    expect(formatTotal(2520)).toBe("42 min");
    expect(formatTotal(4320)).toBe("1 h 12 min");
  });
});
