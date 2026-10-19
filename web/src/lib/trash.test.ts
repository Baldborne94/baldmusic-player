import { describe, expect, it } from "vitest";
import { addRecords, MAX_RECORDS, putBack, reconcile, restoreScript, type TrashRecord } from "./trash";

const rec = (path: string, extra: Partial<TrashRecord> = {}): TrashRecord => ({ path, title: path, at: 1, ...extra });

describe("addRecords", () => {
  it("i più recenti in cima, un file una volta sola, al massimo MAX_RECORDS", () => {
    const list = addRecords([rec("a"), rec("b")], [rec("b", { at: 2 }), rec("c", { at: 2 })]);
    expect(list.map((r) => r.path)).toEqual(["b", "c", "a"]);
    expect(list[0].at).toBe(2);
    const many = Array.from({ length: MAX_RECORDS }, (_, i) => rec(`x${i}`));
    expect(addRecords(many, [rec("nuovo")])).toHaveLength(MAX_RECORDS);
  });
});

describe("reconcile", () => {
  it("finché lo script non parte il file c'è ancora e resta in attesa", () => {
    const { records, restored } = reconcile([rec("a")], new Set(["a"]));
    expect(records).toEqual([rec("a")]);
    expect(restored).toEqual([]);
  });

  it("sparito = nel Cestino; sparito e ricomparso = ripristinato", () => {
    const first = reconcile([rec("a"), rec("b")], new Set(["b"]));
    expect(first.records).toEqual([rec("a", { gone: true }), rec("b")]);
    const second = reconcile(first.records, new Set(["a", "b"]));
    expect(second.restored.map((r) => r.path)).toEqual(["a"]);
    expect(second.records.map((r) => r.path)).toEqual(["b"]);
  });
});

describe("putBack", () => {
  it("il file ripristinato riprende il posto della copia tenuta", () => {
    expect(putBack(["x", "tenuto", "y"], "tornato", "tenuto", ["x", "y", "tornato"])).toEqual(["x", "tornato", "y"]);
  });

  it("niente da cambiare se c'è già, se non c'era un doppione o se la copia tenuta è della stessa cartella", () => {
    expect(putBack(["tornato", "tenuto"], "tornato", "tenuto", [])).toBeNull();
    expect(putBack(["x"], "tornato", undefined, [])).toBeNull();
    expect(putBack(["x"], "tornato", "tenuto", [])).toBeNull();
    expect(putBack(["tenuto"], "tornato", "tenuto", ["tenuto", "tornato"])).toBeNull();
  });
});

describe("restoreScript", () => {
  it("parte col doppio clic e legge il Cestino dell'utente, apici raddoppiati", () => {
    const s = restoreScript(["C:\\Music\\Disney\\L'amore è.mp3"]);
    expect(s.startsWith("<# :")).toBe(true);
    expect(s).toContain("'C:\\Music\\Disney\\L''amore è.mp3'");
    expect(s).toContain("'$Recycle.Bin'");
    expect(s).toContain("WindowsIdentity");
    // Mai sovrascrivere un file che c'è già.
    expect(s.indexOf("Test-Path -LiteralPath $f")).toBeLessThan(s.indexOf("[IO.File]::Move"));
  });
});
