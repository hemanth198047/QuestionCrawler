import type { HealthResponse } from '../types/health'
import { apiGet } from './client'

export const getHealth = (signal?: AbortSignal) => apiGet<HealthResponse>('/health', signal)
