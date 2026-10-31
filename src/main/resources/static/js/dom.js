/** Builds an element with text/element children only (never innerHTML), so server data can't inject markup. */
export function el(tag, attrs, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs)) {
    if (value === false || value === null || value === undefined) continue;
    node.setAttribute(key, value === true ? '' : value);
  }
  node.append(...children);
  return node;
}
