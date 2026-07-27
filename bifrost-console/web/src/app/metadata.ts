export type BuildMetadata = Readonly<{
  version: string;
}>;

export const buildMetadata: BuildMetadata = {
  version: import.meta.env.VITE_BIFROST_VERSION,
};
