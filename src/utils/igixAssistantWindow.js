const MESSAGE_TYPE = 'IGIX_ASSISTANT_WINDOW'
const STATE_MESSAGE_TYPE = 'IGIX_ASSISTANT_WINDOW_STATE'

function resolveParentOrigin() {
  if (document.referrer) {
    try {
      return new URL(document.referrer).origin
    } catch {
      // Fall through to the known iGIX platform origin.
    }
  }

  return 'http://172.17.3.34:5300'
}

export function createIgixAssistantWindow(options = {}) {
  const parentOrigin = options.parentOrigin || resolveParentOrigin()
  const stateListeners = new Set()
  let unbindEscape = null

  function send(action) {
    if (window.parent === window) {
      console.info('[igix-assistant-window] ignored outside iframe', action)
      return
    }

    window.parent.postMessage(
      {
        type: MESSAGE_TYPE,
        action,
      },
      parentOrigin,
    )
  }

  function handleMessage(event) {
    if (event.source !== window.parent || event.origin !== parentOrigin) return
    if (!event.data || event.data.type !== STATE_MESSAGE_TYPE) return

    stateListeners.forEach((listener) => listener(event.data.state))
  }

  window.addEventListener('message', handleMessage)

  const api = {
    open: () => send('open'),
    minimize: () => send('minimize'),
    close: () => send('close'),
    compact: () => send('compact'),
    wide: () => send('wide'),
    toggleWide: () => send('toggle-wide'),
    fullscreen: () => send('fullscreen'),
    toggleFullscreen: () => send('toggle-fullscreen'),
    restore: () => send('restore'),
    escape: () => send('escape'),
    getState: () => send('get-state'),

    onStateChange(listener) {
      stateListeners.add(listener)
      return () => stateListeners.delete(listener)
    },

    bindEscape() {
      if (unbindEscape) return unbindEscape

      const handleKeydown = (event) => {
        if (event.key === 'Escape') api.escape()
      }

      window.addEventListener('keydown', handleKeydown)
      unbindEscape = () => {
        window.removeEventListener('keydown', handleKeydown)
        unbindEscape = null
      }
      return unbindEscape
    },

    destroy() {
      if (unbindEscape) unbindEscape()
      stateListeners.clear()
      window.removeEventListener('message', handleMessage)
    },
  }

  return api
}
