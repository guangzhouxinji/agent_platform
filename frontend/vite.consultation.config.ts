import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { resolve } from "node:path";
export default defineConfig({base:"/app/",plugins:[react()],build:{outDir:"dist-consultation",emptyOutDir:true,sourcemap:true,rollupOptions:{input:resolve(import.meta.dirname,"consultation.html")}}});
