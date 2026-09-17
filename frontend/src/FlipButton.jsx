/* Real button element (not an anchor): keeps native button semantics,
 * keyboard operability, and screen-reader roles. Faces render from
 * data-front / data-back; aria-label carries the action name. */
export default function FlipButton({
  front,
  back,
  tone = "neutral",
  size = "md",
  className = "",
  ...props
}) {
  const label = props["aria-label"] || front;
  return (
    <button
      type="button"
      className={`btn-flip btn-flip--${tone} btn-flip--${size}${className ? ` ${className}` : ""}`}
      data-front={front}
      data-back={back ?? front}
      aria-label={label}
      {...props}
    />
  );
}
