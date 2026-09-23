const STORAGE_KEY = 'wes-site-tour-completed-v1'

export function hasCompletedSiteTour(): boolean {
  try {
    return window.localStorage.getItem(STORAGE_KEY) === 'true'
  } catch {
    return false
  }
}

export function completeSiteTour(): void {
  try {
    window.localStorage.setItem(STORAGE_KEY, 'true')
  } catch {
    // Тур остаётся доступен в текущей вкладке, если хранилище отключено.
  }
}
