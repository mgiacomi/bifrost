/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_BIFROST_VERSION: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
