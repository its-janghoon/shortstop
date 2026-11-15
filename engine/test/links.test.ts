import { describe, expect, it } from "vitest";
import { checkLink, extractLinks } from "../src/index";

describe("links", () => {
  it("finds links with and without scheme, not e-mail domains", () => {
    expect(extractLinks("Pay at acme-parcel-redelivery.co/pay now. Mail me at a@b.com.")).toEqual([
      "acme-parcel-redelivery.co/pay",
    ]);
    expect(extractLinks("See https://acmebank.com/login.")).toEqual(["https://acmebank.com/login"]);
  });

  it("trusts the real domain", () => {
    expect(checkLink("https://www.acmebank.com/login").reasons).toEqual([]);
  });

  it("flags look-alike hosts", () => {
    expect(checkLink("https://acrnebank.com").reasons.join()).toMatch(/looks like acmebank.com/);
    expect(checkLink("paypa1.com").reasons.join()).toMatch(/looks like paypal.com/);
    expect(checkLink("acmebank-secure-login.xyz").reasons.join()).toMatch(/looks like acmebank.com/);
    expect(checkLink("acmebank.com.verify-account.top").reasons.join()).toMatch(/\.top/);
  });

  it("flags punycode, IPs, shorteners and plain http", () => {
    expect(checkLink("https://xn--acmebnk-8ya.com").reasons.join()).toMatch(/another alphabet/);
    expect(checkLink("http://192.168.0.10/login").reasons.join()).toMatch(/IP address/);
    expect(checkLink("bit.ly/3abc").reasons.join()).toMatch(/shortened/);
    expect(checkLink("http://example.org").reasons.join()).toMatch(/not secure/);
  });
});
