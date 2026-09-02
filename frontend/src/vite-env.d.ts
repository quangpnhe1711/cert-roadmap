/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Origin of the API. Empty means same-origin via the dev proxy. */
  readonly VITE_API_BASE_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
