const { json } = require("./_util");
const USER = "whitekid-tech";

exports.handler = async () => {
  const h = { "User-Agent": "whitekid-tech-site", Accept: "application/vnd.github+json" };
  if (process.env.GITHUB_TOKEN) h.Authorization = "Bearer " + process.env.GITHUB_TOKEN;
  const get = async (p) => {
    const r = await fetch("https://api.github.com" + p, { headers: h });
    if (!r.ok) throw new Error("GitHub " + r.status);
    return r.json();
  };
  try {
    const [u, repos, ev] = await Promise.all([
      get("/users/" + USER),
      get("/users/" + USER + "/repos?sort=pushed&per_page=6&type=owner"),
      get("/users/" + USER + "/events/public?per_page=10"),
    ]);
    return json(
      200,
      {
        profile: { login: u.login, url: u.html_url, repos: u.public_repos, followers: u.followers, following: u.following },
        repos: repos.map((r) => ({ name: r.name, url: r.html_url, desc: r.description, lang: r.language, stars: r.stargazers_count, pushed: r.pushed_at })),
        events: ev.map((e) => ({ type: e.type, repo: e.repo.name, at: e.created_at, commits: e.payload && e.payload.commits ? e.payload.commits.length : undefined })),
      },
      { "Cache-Control": "public, max-age=300" }
    );
  } catch (e) {
    return json(502, { error: "GitHub data is unavailable right now (" + e.message + ")." });
  }
};
