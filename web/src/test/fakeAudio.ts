// Un <audio> finto per provare player.ts in Node: niente suono, solo le proprietà e gli
// eventi che il player usa. I test lo fanno "suonare" a mano con ready(), at() ed end().

export class FakeAudio extends EventTarget {
  static all: FakeAudio[] = [];
  preload = "";
  volume = 1;
  currentTime = 0;
  duration = NaN;
  readyState = 0;
  paused = true;
  playbackRate = 1;
  loads = 0;
  private source = "";

  constructor() {
    super();
    FakeAudio.all.push(this);
  }

  get src(): string {
    return this.source;
  }

  set src(v: string) {
    this.source = v;
    this.currentTime = 0;
    this.duration = NaN;
    this.readyState = 0;
  }

  removeAttribute(name: string): void {
    if (name === "src") this.src = "";
  }

  load(): void {
    this.loads++;
  }

  play(): Promise<void> {
    if (this.paused) {
      this.paused = false;
      this.fire("play");
    }
    return Promise.resolve();
  }

  pause(): void {
    if (this.paused) return;
    this.paused = true;
    this.fire("pause");
  }

  fire(type: string): void {
    this.dispatchEvent(new Event(type));
  }

  /** Il brano è caricato: si sa quanto dura e si può suonare. */
  ready(duration: number): this {
    this.duration = duration;
    this.readyState = 4;
    this.fire("durationchange");
    this.fire("canplay");
    return this;
  }

  /** La riproduzione arriva a [seconds]. */
  at(seconds: number): this {
    this.currentTime = seconds;
    this.fire("timeupdate");
    return this;
  }

  /** Il brano finisce da solo. */
  end(): void {
    this.currentTime = this.duration;
    this.paused = true;
    this.fire("ended");
  }
}

/** L'ID del brano che un <audio> sta caricando (dall'indirizzo di stream). */
export const streamedId = (a: FakeAudio | undefined) => (a?.src ? new URL(a.src).searchParams.get("id") : null);
