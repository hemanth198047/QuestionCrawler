import { AppWindow } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { getSignInWindow, showSignInWindow } from '../api'
import { Banner, Button } from './ui'

/**
 * While a browser sign-in window is open (company login page), says so and offers to bring it to the front: a window
 * opened by the app can end up behind this one, and the person may not notice it.
 */
export function SignInWindowHint({ active }: { active: boolean }) {
  const [open, setOpen] = useState(false)
  const wasOpen = useRef(false)
  useEffect(() => {
    if (!active) { setOpen(false); return }
    let stop = false
    const poll = async () => {
      try {
        const r = await getSignInWindow()
        if (stop) return
        if (wasOpen.current && !r.open) window.focus() // signed in: back to the app
        wasOpen.current = r.open
        setOpen(r.open)
      } catch { /* the hint is a convenience */ }
      if (!stop) window.setTimeout(poll, 1200)
    }
    void poll()
    return () => { stop = true }
  }, [active])
  if (!open) return null
  return (
    <Banner tone="info" title="Finish signing in in the browser window"
      action={<Button size="sm" icon={<AppWindow size={15} />} onClick={() => { void showSignInWindow() }}>Show the sign-in window</Button>}>
      A browser window opened with your company login page. Sign in there as usual (password, MFA). It closes by itself when the site lets you in, and the app carries on.
      If you do not see it, press “Show the sign-in window”.
    </Banner>
  )
}
