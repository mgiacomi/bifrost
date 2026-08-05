/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_LOOMSPAN_VERSION: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
