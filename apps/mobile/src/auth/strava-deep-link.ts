import { Linking } from "react-native";

export function listenForStravaCallback(exchangeTicket: (ticket: string) => Promise<void>) {
  const handle = async (url: string) => {
    const parsed = new URL(url);
    if (parsed.protocol !== "bikegps:" || parsed.hostname !== "oauth" || parsed.pathname !== "/strava") return;
    const ticket = parsed.searchParams.get("ticket");
    if (!ticket) throw new Error("OAUTH_TICKET_MISSING");
    await exchangeTicket(ticket);
  };
  const listener = Linking.addEventListener("url", event => void handle(event.url));
  void Linking.getInitialURL().then(url => url && handle(url));
  return () => listener.remove();
}

