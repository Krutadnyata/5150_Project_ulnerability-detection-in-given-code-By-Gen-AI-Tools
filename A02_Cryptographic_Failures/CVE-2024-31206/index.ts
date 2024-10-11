import { Buffer } from 'node:buffer';
import { URL, URLSearchParams } from 'node:url';

/**
 * @param text The text to send to the aeiou Dectalk API
 * @returns A buffer containing wav-encoded binary output
 * @throws If `text` is empty or only whitespace
 * @throws If the API returns a non-200 response
 */
export default async function dectalk(text: string): Promise<Buffer> {
	// The API does not like empty prompts
	text = text.trim();
	if (text.length === 0) {
		throw new TypeError('Text cannot be empty or only whitespace');
	}

	// Format request URL
	const url = new URL('http://tts.cyzon.us/tts');
	url.search = new URLSearchParams({ text }).toString();

	// Send request
	const response = await fetch(url);

	// TODO look into custom behavior for aeiou's error codes
	// https://github.com/calzoneman/aeiou/blob/master/docs/usage-guidelines.md
	if (!response.ok) {
		throw new Error(`${response.status} ${response.statusText}`, { cause: response });
	}

	// Parse and return response
	const binaryData = await response.arrayBuffer();
	return Buffer.from(binaryData);
}
