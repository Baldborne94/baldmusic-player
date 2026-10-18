import { useState } from "react";
import { useClient } from "../lib/session";
import { Icon } from "./Icon";

/** Copertina dal server, con segnaposto se manca o non si carica. */
export function Cover({ id, size = 300, alt = "", round = false, className = "" }: { id?: string; size?: number; alt?: string; round?: boolean; className?: string }) {
  const client = useClient();
  const [failed, setFailed] = useState<string | null>(null);
  const src = client?.coverUrl(id, size);
  const cls = `cover ${round ? "cover--round" : ""} ${className}`;
  if (!src || failed === src) {
    return (
      <div className={`${cls} cover--empty`} aria-hidden="true">
        <Icon name="music" size={36} />
      </div>
    );
  }
  return <img className={cls} src={src} alt={alt} loading="lazy" decoding="async" onError={() => setFailed(src)} />;
}
