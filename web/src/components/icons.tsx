import type { ReactNode } from "react";

interface IconProps {
  size?: number;
  className?: string;
}

function base(size: number, className: string | undefined, children: ReactNode, filled = false) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill={filled ? "currentColor" : "none"}
      stroke={filled ? "none" : "currentColor"}
      strokeWidth={2}
      strokeLinecap="round"
      strokeLinejoin="round"
      className={className}
      aria-hidden="true"
    >
      {children}
    </svg>
  );
}

export const PlusIcon = ({ size = 20, className }: IconProps) =>
  base(size, className, (
    <>
      <line x1="12" y1="5" x2="12" y2="19" />
      <line x1="5" y1="12" x2="19" y2="12" />
    </>
  ));

export const SettingsIcon = ({ size = 20, className }: IconProps) =>
  base(size, className, (
    <>
      <circle cx="12" cy="12" r="3" />
      <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z" />
    </>
  ));

export const ArrowLeftIcon = ({ size = 20, className }: IconProps) =>
  base(size, className, (
    <>
      <line x1="19" y1="12" x2="5" y2="12" />
      <polyline points="12 19 5 12 12 5" />
    </>
  ));

export const SendIcon = ({ size = 18, className }: IconProps) =>
  base(size, className, (
    <>
      <line x1="22" y1="2" x2="11" y2="13" />
      <polygon points="22 2 15 22 11 13 2 9 22 2" />
    </>
  ));

export const StopIcon = ({ size = 14, className }: IconProps) =>
  <rect width={size} height={size} x={(24 - size) / 2} y={(24 - size) / 2} rx="2" fill="currentColor" className={className} />;

export const MicIcon = ({ size = 16, className }: IconProps) =>
  base(size, className, (
    <>
      <path d="M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z" />
      <path d="M19 10v2a7 7 0 0 1-14 0v-2" />
      <line x1="12" y1="19" x2="12" y2="23" />
      <line x1="8" y1="23" x2="16" y2="23" />
    </>
  ));

export const SearchIcon = ({ size = 16, className }: IconProps) =>
  base(size, className, (
    <>
      <circle cx="11" cy="11" r="8" />
      <line x1="21" y1="21" x2="16.65" y2="16.65" />
    </>
  ));

export const PlayIcon = ({ size = 16, className }: IconProps) =>
  base(size, className, <polygon points="5 3 19 12 5 21 5 3" />);

export const ChevronDownIcon = ({ size = 16, className }: IconProps) =>
  base(size, className, <polyline points="6 9 12 15 18 9" />);

export const ChevronUpIcon = ({ size = 16, className }: IconProps) =>
  base(size, className, <polyline points="18 15 12 9 6 15" />);

export const ChevronLeftIcon = ({ size = 16, className }: IconProps) =>
  base(size, className, <polyline points="15 18 9 12 15 6" />);

export const ChevronRightIcon = ({ size = 16, className }: IconProps) =>
  base(size, className, <polyline points="9 18 15 12 9 6" />);

export const CheckIcon = ({ size = 16, className }: IconProps) =>
  base(size, className, <polyline points="20 6 9 17 4 12" />);

export const MenuIcon = ({ size = 18, className }: IconProps) =>
  base(size, className, (
    <>
      <line x1="3" y1="6" x2="21" y2="6" />
      <line x1="3" y1="12" x2="21" y2="12" />
      <line x1="3" y1="18" x2="21" y2="18" />
    </>
  ));

export const CloseIcon = ({ size = 16, className }: IconProps) =>
  base(size, className, (
    <>
      <line x1="18" y1="6" x2="6" y2="18" />
      <line x1="6" y1="6" x2="18" y2="18" />
    </>
  ));
