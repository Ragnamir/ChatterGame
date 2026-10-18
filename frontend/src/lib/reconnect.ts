/**
 * Метрика `PLAN F02`: время от переподключения до первого сообщения. Хранится в памяти
 * вкладки, а не отправляется на сервер: это диагностика клиента, а не игровое событие.
 */
export interface ReconnectReading {
  startedAt: number;
  firstMessageAt: number;
  elapsedMs: number;
}

export class ReconnectClock {
  private startedAt: number | null = null;
  private reading: ReconnectReading | null = null;

  markDisconnected(now: number): void {
    this.startedAt = now;
    this.reading = null;
  }

  markFirstMessage(now: number): ReconnectReading | null {
    if (this.startedAt === null) {
      return this.reading;
    }
    this.reading = {
      startedAt: this.startedAt,
      firstMessageAt: now,
      elapsedMs: Math.max(0, now - this.startedAt),
    };
    this.startedAt = null;
    return this.reading;
  }

  get last(): ReconnectReading | null {
    return this.reading;
  }

  get pending(): boolean {
    return this.startedAt !== null;
  }
}
