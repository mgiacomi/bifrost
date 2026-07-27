declare module "node:path" {
  const path: {
    dirname(value: string): string;
    join(...values: string[]): string;
    resolve(...values: string[]): string;
  };
  export default path;
}

declare module "node:url" {
  export function fileURLToPath(value: string | URL): string;
}

declare module "node:fs" {
  const fs: {
    readFileSync(path: string, encoding: "utf8"): string;
  };
  export default fs;
}

declare const process: {
  env: Record<string, string | undefined>;
  platform: string;
};

interface ImportMeta {
  readonly url: string;
}
