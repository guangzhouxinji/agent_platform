import type { ThemeConfig } from "antd";

export const theme: ThemeConfig = {
  token: {
    colorPrimary: "#1558d5",
    colorInfo: "#1558d5",
    colorWarning: "#9b5b11",
    colorError: "#b83a3a",
    colorText: "#13243c",
    colorTextSecondary: "#64758a",
    colorBgLayout: "#f6f8fb",
    colorBgContainer: "#ffffff",
    colorBorder: "#d8e2ed",
    colorBorderSecondary: "#e7edf4",
    borderRadius: 3,
    borderRadiusLG: 4,
    controlHeight: 36,
    fontSize: 14,
    fontFamily: "'Microsoft YaHei UI', 'PingFang SC', 'Segoe UI', sans-serif"
  },
  components: {
    Button: { controlHeight: 36, fontWeight: 500 },
    Form: { itemMarginBottom: 16, labelColor: "#33413e" },
    Input: { activeShadow: "0 0 0 2px rgba(21,88,213,0.12)" },
    Select: { activeOutlineColor: "rgba(21,88,213,0.12)" },
    Table: { headerBg: "#f4f7fb", headerColor: "#3e5068", rowHoverBg: "#f5f9ff" },
    Tabs: { itemSelectedColor: "#1558d5", inkBarColor: "#1558d5" },
    Drawer: { paddingLG: 20 },
    Layout: { bodyBg: "#f6f8fb", headerBg: "#ffffff", siderBg: "#ffffff" },
    Menu: {
      darkItemBg: "#ffffff",
      darkItemColor: "#51647b",
      darkItemHoverBg: "#edf4ff",
      darkItemSelectedBg: "#e8f1ff",
      darkItemSelectedColor: "#1558d5",
      itemBorderRadius: 4,
      itemMarginInline: 8
    }
  }
};
