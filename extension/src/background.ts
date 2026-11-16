/// <reference types="chrome" />
// Context menus open the side panel with the chosen text. The check itself runs in the side panel,
// where the on-device model is available.

const MENU = {
  selection: "shortstop-selection",
  link: "shortstop-link",
  page: "shortstop-page",
} as const;

chrome.runtime.onInstalled.addListener(() => {
  chrome.contextMenus.create({ id: MENU.selection, title: "Check with Shortstop", contexts: ["selection"] });
  chrome.contextMenus.create({ id: MENU.link, title: "Check this link with Shortstop", contexts: ["link"] });
  chrome.contextMenus.create({ id: MENU.page, title: "Check this page with Shortstop", contexts: ["page"] });
});

chrome.action.onClicked.addListener((tab) => {
  if (tab.windowId !== undefined) void chrome.sidePanel.open({ windowId: tab.windowId });
});

async function pageText(tabId: number): Promise<string> {
  const [res] = await chrome.scripting.executeScript({
    target: { tabId },
    // Only visible text, capped. Runs once, on user request.
    func: () => (document.body?.innerText ?? "").slice(0, 6000),
  });
  return (res?.result as string | undefined) ?? "";
}

chrome.contextMenus.onClicked.addListener((info, tab) => {
  if (!tab?.id || tab.windowId === undefined) return;
  // sidePanel.open must run inside the user gesture, before any await.
  void chrome.sidePanel.open({ windowId: tab.windowId });
  void (async () => {
    let text = "";
    if (info.menuItemId === MENU.selection) text = info.selectionText ?? "";
    else if (info.menuItemId === MENU.link) text = info.linkUrl ?? "";
    else if (info.menuItemId === MENU.page) text = await pageText(tab.id!);
    // Pages with ?demo= (the contest video's fake inbox) put the side panel in demo mode too.
    const demo = /[?&]demo=/.test(info.pageUrl ?? tab.url ?? "");
    await chrome.storage.session.set({ pending: { text, at: Date.now(), demo } });
  })();
});
