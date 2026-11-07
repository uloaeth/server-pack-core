type Geo = { country?: string; countryCode?: string; city?: string; lat?: number; lon?: number };

let lookup: ((ip: string) => Geo | null) | null = null;

export async function initGeo(): Promise<void> {
  const path = process.env.GEOIP_DB;
  if (!path) return;
  try {
    const maxmind = await import('maxmind');
    const reader = await maxmind.open<{ country?: { names?: { en?: string }; iso_code?: string }; city?: { names?: { en?: string } }; location?: { latitude?: number; longitude?: number } }>(path);
    lookup = (ip) => {
      const hit = reader.get(ip);
      if (!hit) return null;
      return {
        country: hit.country?.names?.en,
        countryCode: hit.country?.iso_code,
        city: hit.city?.names?.en,
        lat: hit.location?.latitude,
        lon: hit.location?.longitude
      };
    };
  } catch {
    lookup = null;
  }
}

export function geo(ip: string | null | undefined): Geo {
  if (!ip || !lookup) return {};
  try {
    return lookup(ip) ?? {};
  } catch {
    return {};
  }
}
