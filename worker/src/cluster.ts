/**
 * Which network the worker's RPC is really on.
 *
 * CLUSTER says which network payments and sends are for, but RPC_URL is a
 * separate secret, and nothing stopped them disagreeing: a devnet CLUSTER with a
 * mainnet RPC hands the phone mainnet blockhashes, and Seed Vault, set to devnet,
 * rightly refuses the transaction. The RPC's genesis hash settles it, once per
 * RPC address for the life of the worker.
 */
import { rpcCall } from './solana.ts'

export const GENESIS_HASHES: Record<string, string> = {
  '5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d': 'mainnet-beta',
  EtWTRABZaYq6iMfeYKouRu166VU2xqa1wcaWoxPkrZBG: 'devnet',
  '4uhcVJyU9pJkvQyS88uRDiswHXSCkY3zQawwpjk2NsNY': 'testnet',
}

const known = new Map<string, string>()

/** For tests: forget what each RPC turned out to be. */
export function forgetRpcClusters(): void {
  known.clear()
}

/** The RPC's network, or null when it cannot be told (a local validator, or no answer). */
export async function rpcCluster(url: string): Promise<string | null> {
  const remembered = known.get(url)
  if (remembered) return remembered
  try {
    const name = GENESIS_HASHES[String(await rpcCall(url, 'getGenesisHash', []))]
    if (name) known.set(url, name)
    return name ?? null
  } catch {
    return null
  }
}

/** A plain sentence when the RPC is on another network than CLUSTER; null when they agree or it cannot be told. */
export async function rpcClusterMismatch(url: string, cluster: string): Promise<string | null> {
  const actual = await rpcCluster(url)
  if (!actual || actual === cluster) return null
  return `Heylana's server is set up for ${cluster} but connected to ${actual}, so nothing can be sent until that is fixed.`
}
