export function Loading() {
  return (
    <div className="state">
      <span className="spinner" />
    </div>
  );
}

export function ErrorState({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return (
    <div className="state">
      <p className="error">{message}</p>
      {onRetry && (
        <button className="btn" onClick={onRetry}>
          Riprova
        </button>
      )}
    </div>
  );
}
