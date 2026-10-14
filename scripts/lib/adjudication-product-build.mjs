// A public OAuth client identifier is not a credential or a registration receipt.
// The deployment operator must bind it to the current domain registration before release.
export function requireAdjudicationClientId(env) {
  const value=env.VITE_ADJUDICATION_CLIENT_ID;
  if(typeof value!=='string'||!/^[A-Za-z0-9][A-Za-z0-9._:-]{0,99}$/.test(value))throw Error('CURRENT_ADJUDICATION_CLIENT_BINDING_REQUIRED');
  return value;
}
