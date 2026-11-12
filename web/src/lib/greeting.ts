/**
 * Il saluto in cima alla Home: l'ora del giorno e il nome dell'utente collegato a Navidrome
 * («Buonasera, Alessandro»). Stessa regola di `Greeting` sul telefono.
 */
export function greeting(hour: number, user?: string | null): string {
  const time = hour >= 5 && hour < 13 ? "Buongiorno" : hour >= 13 && hour < 18 ? "Buon pomeriggio" : "Buonasera";
  const name = displayName(user);
  return name ? `${time}, ${name}` : time;
}

/**
 * Il nome dell'utente da mostrare: quello con cui ci si è collegati, senza la parte dopo la @
 * di un indirizzo, con punti, trattini e trattini bassi come spazi e l'iniziale maiuscola
 * («mario.rossi» → «Mario Rossi»). Le maiuscole che ci sono già restano («DJBald»).
 */
export function displayName(user?: string | null): string {
  const base = (user ?? "").trim().split("@")[0];
  return base
    .split(/[._\-\s]+/)
    .filter(Boolean)
    .map((w) => w.charAt(0).toLocaleUpperCase("it") + w.slice(1))
    .join(" ");
}
