import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { RouterProvider } from "react-router";
import { browserRouter } from "./app/routes";
import "./styles/index.css";

const root = document.getElementById("root");
if (!root) throw new Error("Bifrost Console root element is missing");

createRoot(root).render(
  <StrictMode>
    <RouterProvider router={browserRouter()} />
  </StrictMode>,
);
